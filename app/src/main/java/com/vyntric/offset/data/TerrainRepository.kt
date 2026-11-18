/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.Terrarium
import com.vyntric.offset.propagation.TileId
import com.vyntric.offset.propagation.TiledTerrain
import com.vyntric.offset.propagation.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Elevation tiles from the AWS Open Data terrain tiles (Mapzen's Terrarium encoding), fetched
 * for the area being mapped and kept on the phone so the same area works offline afterwards.
 * Only tile coordinates are requested: nothing about the user or their position is sent beyond
 * which tiles are needed.
 */
class TerrainRepository(
    private val client: OkHttpClient,
    private val dir: File,
    private val userAgent: String,
    /** PNG bytes to ARGB pixels, or null when they are not a 256 x 256 image. */
    private val decodePng: (ByteArray) -> IntArray?,
    private val baseUrl: String = DEFAULT_URL,
) {
    /** How a load went: the terrain, and whether some of it could not be had. */
    data class Loaded(val terrain: TiledTerrain, val downloaded: Int, val missing: Int) {
        val complete: Boolean get() = missing == 0
    }

    private val memory = object : LinkedHashMap<TileId, ShortArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, ShortArray>) = size > MEMORY_TILES
    }
    private val memoryLock = Mutex()
    private val fetches = Semaphore(PARALLEL_FETCHES)

    /**
     * Terrain for the square of half side [radiusM] around [center]. Uses tiles on the phone
     * first and fetches the rest. When some cannot be fetched (offline), falls back to a coarser
     * zoom that is fully on the phone, and failing that returns what there is with [Loaded.missing].
     */
    suspend fun load(center: GeoPoint, radiusM: Double, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Loaded {
        // Never coarser than offline downloads save, so a saved state always serves the load.
        val zoom = Tiles.zoomFor(center, radiusM).coerceIn(MIN_FALLBACK_ZOOM, MAX_ZOOM)
        val ids = Tiles.covering(center, radiusM, zoom)
        val done = AtomicInteger()
        val downloaded = AtomicInteger()
        // The first network failure means offline (or a hanging connection): stop asking, use the phone.
        val unreachable = java.util.concurrent.atomic.AtomicBoolean(false)
        val tiles = coroutineScope {
            ids.map { id ->
                async(Dispatchers.IO) {
                    val tile = tile(id, allowNetwork = true, unreachable = unreachable) { downloaded.incrementAndGet() }
                    onProgress(done.incrementAndGet(), ids.size)
                    id to tile
                }
            }.awaitAll()
        }
        val missing = tiles.count { it.second == null }
        if (missing > 0) {
            for (z in zoom - 1 downTo MIN_FALLBACK_ZOOM) {
                val coarse = Tiles.covering(center, radiusM, z).map { it to tile(it, allowNetwork = false) }
                if (coarse.all { it.second != null }) {
                    return Loaded(TiledTerrain(z, coarse.associate { it.first to it.second!! }), downloaded.get(), 0)
                }
            }
        }
        val present = tiles.mapNotNull { (id, t) -> t?.let { id to it } }.toMap()
        return Loaded(TiledTerrain(zoom, present, missing), downloaded.get(), missing)
    }

    /**
     * Saves every tile in the box for offline use, at the zooms coverage maps use. Returns the
     * number of tiles that could not be fetched.
     */
    suspend fun download(
        north: Double, west: Double, south: Double, east: Double,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val ids = tilesInBox(north, west, south, east)
        val done = AtomicInteger()
        val failed = AtomicInteger()
        // The first network failure ends the download: the rest would fail the same way.
        val unreachable = java.util.concurrent.atomic.AtomicBoolean(false)
        // A large state is thousands of tiles: work through them in batches, never all at once.
        for (batch in ids.chunked(DOWNLOAD_BATCH)) {
            if (unreachable.get()) break
            coroutineScope {
                batch.map { id ->
                    async(Dispatchers.IO) {
                        if (!file(id).exists() && fetch(id, unreachable) == null) failed.incrementAndGet()
                        onProgress(done.incrementAndGet(), ids.size)
                    }
                }.awaitAll()
            }
        }
        return failed.get() + (ids.size - done.get())
    }

    /**
     * The tiles [download] fetches for the box: the box itself plus [OFFLINE_MARGIN_M] all round,
     * because a coverage map near a border reaches over it. Also used for size estimates.
     */
    fun tilesInBox(north: Double, west: Double, south: Double, east: Double): List<TileId> =
        (MIN_FALLBACK_ZOOM..OFFLINE_ZOOM).flatMap { z ->
            val dLat = Math.toDegrees(OFFLINE_MARGIN_M / com.vyntric.offset.propagation.Earth.RADIUS_M)
            val dLon = dLat / kotlin.math.cos(Math.toRadians(maxOf(kotlin.math.abs(north), kotlin.math.abs(south)).coerceAtMost(80.0)))
            val max = (1 shl z) - 1
            fun column(lon: Double) = (Tiles.pixel(0.0, lon, z).first / Tiles.SIZE).toInt().coerceIn(0, max)
            val y0 = (Tiles.pixel((north + dLat).coerceAtMost(85.0), 0.0, z).second / Tiles.SIZE).toInt().coerceIn(0, max)
            val y1 = (Tiles.pixel((south - dLat).coerceAtLeast(-85.0), 0.0, z).second / Tiles.SIZE).toInt().coerceIn(0, max)
            val w = west - dLon
            val e = east + dLon
            // Past 180 degrees the margin wraps to the other edge of the world (Alaska's Aleutians).
            val xs = when {
                e - w >= 360.0 -> (0..max).toList()
                w < -180.0 -> (column(w + 360.0)..max) + (0..column(e))
                e > 180.0 -> (column(w)..max) + (0..column(e - 360.0))
                else -> (column(w)..column(e)).toList()
            }.distinct()
            xs.flatMap { x -> (y0..y1).map { y -> TileId(z, x, y) } }
        }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    suspend fun clear() {
        memoryLock.withLock { memory.clear() }
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private suspend fun tile(
        id: TileId,
        allowNetwork: Boolean,
        unreachable: java.util.concurrent.atomic.AtomicBoolean? = null,
        onDownloaded: () -> Unit = {},
    ): ShortArray? {
        memoryLock.withLock { memory[id] }?.let { return it }
        val saved = withContext(Dispatchers.IO) { file(id).takeIf { it.exists() }?.readBytes() }
        var pixels = saved?.let(decodePng)
        if (saved != null && pixels == null) {
            // A damaged file on the phone: drop it and fetch it again rather than going without.
            withContext(Dispatchers.IO) { file(id).delete() }
        }
        if (pixels == null && allowNetwork) {
            pixels = fetch(id, unreachable)?.also { onDownloaded() }?.let(decodePng)
            if (pixels == null) withContext(Dispatchers.IO) { file(id).delete() }
        }
        if (pixels == null) return null
        val elevations = Terrarium.decode(pixels)
        memoryLock.withLock { memory[id] = elevations }
        return elevations
    }

    /**
     * Fetches one tile and saves it. Null when offline or the server refuses. Once [unreachable]
     * is set (a fetch in this load failed on the network), fetches still waiting for a slot give up.
     */
    private suspend fun fetch(id: TileId, unreachable: java.util.concurrent.atomic.AtomicBoolean? = null): ByteArray? = fetches.withPermit {
        if (unreachable?.get() == true) return@withPermit null
        val request = Request.Builder().url("$baseUrl/${id.z}/${id.x}/${id.y}.png").header("User-Agent", userAgent).build()
        try {
            Http.execute(client, request) { response ->
                if (!response.isSuccessful) {
                    // The server is struggling (5xx) or asking us to slow down (429): stop this run too.
                    if (response.code >= 500 || response.code == 429) unreachable?.set(true)
                    return@execute null
                }
                val bytes = response.body?.bytes() ?: return@execute null
                // Only real PNGs are kept, so an error page from a proxy is never saved as terrain.
                if (bytes.size > MAX_TILE_BYTES || !isPng(bytes)) return@execute null
                val target = file(id)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
                bytes
            }
        } catch (e: IOException) {
            unreachable?.set(true)
            null
        }
    }

    private fun file(id: TileId) = File(dir, "${id.z}/${id.x}/${id.y}.png")

    private fun isPng(bytes: ByteArray) = bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    companion object {
        const val DEFAULT_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

        /** Tiles are about 100 KB: a slow or dead connection should fail in seconds, not minutes. */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        /** About 60 m per pixel in the US: finer than VHF/UHF prediction needs. */
        const val MAX_ZOOM = 11
        const val OFFLINE_ZOOM = 11

        /** How far past a state's edges an offline download reaches: the largest coverage radius. */
        const val OFFLINE_MARGIN_M = 150_000.0
        const val MIN_FALLBACK_ZOOM = 9
        private const val PARALLEL_FETCHES = 6
        private const val DOWNLOAD_BATCH = 48
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MEMORY_TILES = 160
        private const val MAX_TILE_BYTES = 2 * 1024 * 1024

        /** Rough size of one tile on disk, for download estimates. */
        const val AVERAGE_TILE_BYTES = 100_000L
    }
}
