/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain.reach

import com.vyntric.offset.propagation.CoverageEngine
import com.vyntric.offset.propagation.CoverageGrid
import com.vyntric.offset.propagation.CoverageRequest
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.PathLossModel
import com.vyntric.offset.propagation.TiledTerrain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Models one coverage map: decides the area and the sample spacing, loads the terrain for it,
 * and sweeps the radials on several workers. The grid is handed over as soon as it exists so a
 * map can draw it while it fills in.
 */
class CoverageSweep(
    /** Terrain for the square of half side [Double] metres around [GeoPoint], reporting tiles done and total. */
    private val loadTerrain: suspend (GeoPoint, Double, (Int, Int) -> Unit) -> Loaded,
    private val model: PathLossModel,
    private val workers: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val frameMs: Long = 140,
) {
    data class Loaded(val terrain: TiledTerrain, val complete: Boolean)

    /** What a sweep needs: where, how high both ends are, and on what frequency. */
    data class Setup(val origin: GeoPoint, val myHeightM: Double, val farHeightM: Double, val searchHeightM: Double, val frequencyMhz: Double)

    /** The terrain and spacing a sweep used, for checking single links against the same ground. */
    data class Area(val terrain: TiledTerrain, val radiusM: Double, val stepM: Double, val terrainComplete: Boolean)

    sealed interface Progress {
        data class Terrain(val done: Int, val total: Int) : Progress
        data class Ready(val area: Area) : Progress
        data class Modelling(val grid: CoverageGrid, val fraction: Float) : Progress
    }

    /**
     * Runs the sweep. [onProgress] hears about the terrain, then the area (before any radial, so
     * links can be checked meanwhile), then the grid a few times a second as radials land. Returns
     * the finished grid. Cancelling the caller stops the workers.
     */
    suspend fun run(setup: Setup, onProgress: (Progress) -> Unit): CoverageGrid {
        // The area reaches past the radio horizon of the tallest far end that will be checked.
        val radius = CoverageRequest.radiusFor(setup.myHeightM, maxOf(setup.farHeightM, setup.searchHeightM))
        val loaded = loadTerrain(setup.origin, radius) { done, total -> onProgress(Progress.Terrain(done, total)) }
        val step = CoverageRequest.stepFor(radius, loaded.terrain.resolutionM(setup.origin.latitude))
        onProgress(Progress.Ready(Area(loaded.terrain, radius, step, loaded.complete)))

        val request = CoverageRequest(setup.origin, setup.myHeightM, setup.farHeightM, setup.frequencyMhz, radius, step)
        val engine = CoverageEngine(loaded.terrain, model)
        val grid = engine.newGrid(request)
        onProgress(Progress.Modelling(grid, 0f))
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        withContext(dispatcher) {
            val jobs = List(workers) {
                launch {
                    while (isActive) {
                        val r = next.getAndIncrement()
                        if (r >= grid.radials) break
                        engine.computeRadial(request, grid, r)
                        done.incrementAndGet()
                    }
                }
            }
            val ticker = launch {
                while (isActive) {
                    delay(frameMs)
                    onProgress(Progress.Modelling(grid, done.get().toFloat() / grid.radials))
                }
            }
            jobs.forEach { it.join() }
            ticker.cancel()
        }
        return grid
    }
}
