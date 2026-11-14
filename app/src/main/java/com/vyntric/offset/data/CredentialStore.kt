/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's own RepeaterBook token and contact email. Never logged, never sent anywhere else. */
data class RepeaterBookCredentials(val token: String, val email: String) {
    val isComplete: Boolean get() = token.isNotBlank() && email.contains('@')
}

/**
 * Stores RepeaterBook credentials in EncryptedSharedPreferences (key in the Android Keystore).
 * When the keystore is unusable the store reports [available] = false instead of falling back to
 * plain text.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences? = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        @Suppress("DEPRECATION")
        EncryptedSharedPreferences.create(
            context, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        android.util.Log.w("Offset", "Encrypted storage unavailable: ${e.javaClass.simpleName}")
        null
    }

    val available: Boolean get() = prefs != null

    private val _credentials = MutableStateFlow(read())
    val credentials: StateFlow<RepeaterBookCredentials> = _credentials.asStateFlow()

    fun save(token: String, email: String) {
        val p = prefs ?: return
        p.edit { putString(K_TOKEN, token.trim()); putString(K_EMAIL, email.trim()) }
        _credentials.value = read()
    }

    fun clear() {
        prefs?.edit { clear() }
        _credentials.value = read()
    }

    private fun read() = RepeaterBookCredentials(
        prefs?.getString(K_TOKEN, "").orEmpty(),
        prefs?.getString(K_EMAIL, "").orEmpty(),
    )

    private companion object {
        const val FILE = "repeaterbook_credentials"
        const val K_TOKEN = "token"
        const val K_EMAIL = "email"
    }
}
