/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Prints the cost of the port so coverage-map budgets can be estimated. Not a pass/fail benchmark. */
class ItmTimingTest {
    @Test
    fun `time 10000 calls on a 600 point profile`() {
        val n = 600
        val profile = DoubleArray(n + 3)
        profile[0] = n.toDouble()
        profile[1] = 50.0 // 30 km path
        for (i in 0..n) profile[i + 2] = 250.0 + 60.0 * sin(i / 23.0) + 25.0 * sin(i / 5.3) + 0.08 * i

        fun call(rxHeight: Double) = Itm.pointToPoint(
            30.0, rxHeight, profile, Climate.CONTINENTAL_TEMPERATE, 301.0, 146.52, Polarization.VERTICAL,
            15.0, 0.005, VariabilityMode.MOBILE, 50.0, 50.0, 50.0,
        ).basicTransmissionLossDb

        var sink = 0.0
        repeat(5_000) { sink += call(1.5 + (it % 10) * 0.1) } // warm up the JIT

        val calls = 10_000
        val start = System.nanoTime()
        repeat(calls) { sink += call(1.5 + (it % 10) * 0.1) }
        val elapsedMs = (System.nanoTime() - start) / 1e6

        println("ITM P2P: $calls calls on a $n-point profile took ${"%.1f".format(elapsedMs)} ms " +
            "(${"%.1f".format(elapsedMs * 1000 / calls)} us per call)")
        assertTrue(sink.isFinite())
    }
}
