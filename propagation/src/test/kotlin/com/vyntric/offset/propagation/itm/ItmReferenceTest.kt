/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The port against NTIA's published results and against NTIA's own C++ built locally. */
class ItmReferenceTest {
    private val toleranceDb = 0.1

    @Test
    fun `matches every result NTIA publishes for point-to-point mode`() {
        val cases = ItmReferenceData.ntiaPublished
        assertEquals(15, cases.size)
        val maxError = checkAll(cases)
        println("ITM vs NTIA published: ${cases.size} cases, max |error| = ${"%.4f".format(maxError)} dB")
    }

    @Test
    fun `matches NTIA C++ on the local reference set, including warnings, mode and error codes`() {
        val cases = ItmReferenceData.localReference
        assertTrue("expected at least 30 successful cases", cases.count { it.expectedDb != null } >= 30)
        val maxError = checkAll(cases)
        println("ITM vs NTIA C++ build: ${cases.size} cases, max |error| = ${"%.2e".format(maxError)} dB")
    }

    @Test
    fun `public API gives the same answer as the raw NTIA entry point`() {
        val modes = VariabilityMode.entries.associateBy { it.code }
        val climates = Climate.entries.associateBy { it.code }
        val pols = Polarization.entries.associateBy { it.code }
        val plain = ItmReferenceData.all.filter { it.expectedRtn.let { r -> r == null || r < 1000 } && it.mdvar in modes }
        assertTrue(plain.size >= 20)
        for (c in plain) {
            val viaApi = Itm.pointToPoint(
                c.hTx, c.hRx, c.profile, climates.getValue(c.climate), c.n0, c.fMhz, pols.getValue(c.pol),
                c.epsilon, c.sigma, modes.getValue(c.mdvar), c.time, c.location, c.situation,
            )
            assertEquals(c.id, c.run(), viaApi)
        }
    }

    @Test
    fun `concurrent calls on several threads give the same results as sequential calls`() {
        val cases = ItmReferenceData.all.filter { it.expectedRtn.let { r -> r == null || r < 1000 } }
        val sequential = cases.map { it.run() }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { t ->
                Callable { (0 until 20).flatMap { round -> cases.indices.map { i -> (i + t + round) % cases.size } }.map { it to cases[it].run() } }
            }
            for (future in pool.invokeAll(tasks)) {
                for ((i, result) in future.get()) assertEquals(cases[i].id, sequential[i], result)
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun ntiaMode(mode: PropagationMode) = when (mode) {
        PropagationMode.LINE_OF_SIGHT -> MODE_LINE_OF_SIGHT
        PropagationMode.DIFFRACTION -> MODE_DIFFRACTION
        PropagationMode.TROPOSCATTER -> MODE_TROPOSCATTER
    }

    /** Runs each case, asserts its expectations, and returns the largest loss error seen. */
    private fun checkAll(cases: List<ItmCase>): Double {
        var maxError = 0.0
        val failures = mutableListOf<String>()
        for (c in cases) {
            val rtn = c.expectedRtn
            if (rtn != null && rtn >= 1000) {
                try {
                    val r = c.run()
                    failures += "${c.id}: expected ItmException $rtn, got $r"
                } catch (e: ItmException) {
                    if (e.code != rtn) failures += "${c.id}: expected error $rtn, got ${e.code} (${e.message})"
                }
                continue
            }
            val r = try {
                c.run()
            } catch (e: ItmException) {
                failures += "${c.id}: unexpected ItmException ${e.code} ${e.message}"
                continue
            }
            val err = abs(r.basicTransmissionLossDb - c.expectedDb!!)
            maxError = maxOf(maxError, err)
            if (err > toleranceDb) failures += "${c.id}: loss ${r.basicTransmissionLossDb} dB, expected ${c.expectedDb}"
            c.expectedWarnings?.let { w ->
                if (r.warnings != w) failures += "${c.id}: warnings 0x${r.warnings.toString(16)}, expected 0x${w.toString(16)}"
            }
            c.expectedMode?.let { m ->
                if (ntiaMode(r.mode) != m) failures += "${c.id}: mode ${r.mode}, expected NTIA mode $m"
            }
            if (rtn != null) {
                val gotRtn = if (r.warnings != 0) ItmError.SUCCESS_WITH_WARNINGS else ItmError.SUCCESS
                if (gotRtn != rtn) failures += "${c.id}: return code $gotRtn, expected $rtn"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", "${failures.size} of ${cases.size} cases failed:\n"))
        return maxError
    }
}
