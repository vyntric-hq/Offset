/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.repeaters

import com.vyntric.offset.domain.RepeaterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP clients with explicit timeouts. Neither client caches or logs anything. */
object Http {
    /** For public data (Hearham). Redirects allowed: no credentials are attached. */
    fun publicClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * For authenticated requests (RepeaterBook). Redirects are never followed so the token can
     * never be forwarded to another origin; a 3xx is reported as an error.
     */
    fun authenticatedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Executes [request], cancelling the HTTP call if the coroutine is cancelled. */
    suspend fun <T> execute(client: OkHttpClient, request: Request, handle: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            val response = suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response) else response.close()
                    }
                })
            }
            response.use(handle)
        }

    fun networkError(e: IOException, what: String) =
        RepeaterException.Network("Could not reach $what. Check your connection and try again. (${e.javaClass.simpleName})", e)
}
