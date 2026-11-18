/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.domain.reach.CoverageSweep
import com.vyntric.offset.propagation.FreeSpace
import com.vyntric.offset.propagation.GeoPoint
import com.vyntric.offset.propagation.PathLoss
import com.vyntric.offset.propagation.PathLossModel
import com.vyntric.offset.propagation.TiledTerrain
import com.vyntric.offset.propagation.itm.PropagationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageSweepTest {
    private val freeSpace = PathLossModel { p, _, _, f -> PathLoss(FreeSpace.lossDb(p[0] * p[1], f), PropagationMode.LINE_OF_SIGHT) }
    private val here = GeoPoint(38.25, -85.76)

    @Test
    fun `a sweep reports terrain, then the area, then a filling grid, and returns it complete`() = runBlocking {
        var askedRadius = 0.0
        val sweep = CoverageSweep(
            loadTerrain = { _, radius, progress ->
                askedRadius = radius
                progress(1, 2); progress(2, 2)
                CoverageSweep.Loaded(TiledTerrain(11, emptyMap()), complete = false)
            },
            model = freeSpace, workers = 3, dispatcher = Dispatchers.Default, frameMs = 1,
        )
        val events = mutableListOf<CoverageSweep.Progress>()
        val grid = sweep.run(CoverageSweep.Setup(here, myHeightM = 10.0, farHeightM = 1.5, searchHeightM = 30.0, frequencyMhz = 146.0)) {
            synchronized(events) { events += it }
        }
        assertEquals(1f, grid.completeness(), 0f)
        // The area reaches the horizon of the tallest far end that will be checked (repeaters at 30 m).
        assertEquals(com.vyntric.offset.propagation.CoverageRequest.radiusFor(10.0, 30.0), askedRadius, 1e-9)
        val kinds = events.map { it::class.simpleName }
        assertEquals(listOf("Terrain", "Terrain", "Ready"), kinds.take(3))
        val ready = events[2] as CoverageSweep.Progress.Ready
        assertTrue(!ready.area.terrainComplete)
        assertTrue(events.drop(3).all { it is CoverageSweep.Progress.Modelling })
    }
}
