/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Ported from NTIA's Irregular Terrain Model (https://github.com/NTIA/itm), a public domain
 * work of the U.S. Government. Changed 2026: translated from C++ to Kotlin.
 */
package com.vyntric.offset.propagation.itm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** How [Itm.pointToPoint] reads the pfl buffer: length from profile[0] only, never written. */
class ItmProfileTest {
    private fun call(profile: DoubleArray) = Itm.pointToPoint(
        txHeightM = 30.0, rxHeightM = 1.5, profile = profile,
        climate = Climate.CONTINENTAL_TEMPERATE, surfaceRefractivity = 301.0, frequencyMhz = 146.0,
        polarization = Polarization.VERTICAL, groundPermittivity = 15.0, groundConductivity = 0.005,
        variability = VariabilityMode.MOBILE, timePercent = 50.0, locationPercent = 50.0, situationPercent = 50.0,
    )

    /** Every successful reference case, so all terrain code paths (LOS, diffraction, tropo, tiny paths) are read. */
    private val cases = ItmReferenceData.all.filter { it.expectedRtn.let { r -> r == null || r < 1000 } }

    @Test
    fun `an oversized buffer with trailing garbage gives the same result as an exact-size one`() {
        for (c in cases) {
            val n = c.profile[0].toInt()
            val exact = c.profile.copyOf(n + 3)
            val oversized = DoubleArray(n + 3 + 500) { i -> if (i < n + 3) exact[i] else 1.0e6 * (if (i % 2 == 0) 1 else -1) }
            val expected = c.run()
            val viaExact = Itm.pointToPointRaw(
                c.hTx, c.hRx, exact, c.climate, c.n0, c.fMhz, c.pol, c.epsilon, c.sigma, c.mdvar, c.time, c.location, c.situation,
            )
            val viaOversized = Itm.pointToPointRaw(
                c.hTx, c.hRx, oversized, c.climate, c.n0, c.fMhz, c.pol, c.epsilon, c.sigma, c.mdvar, c.time, c.location, c.situation,
            )
            assertEquals(c.id, expected, viaExact)
            assertEquals(c.id, viaExact, viaOversized)
        }
    }

    @Test
    fun `a reused buffer only changes n between calls and each call sees just its own prefix`() {
        val buffer = DoubleArray(1000) { i -> if (i < 2) 0.0 else 200.0 + 40.0 * kotlin.math.sin(i / 9.0) }
        buffer[1] = 100.0
        for (n in intArrayOf(400, 50, 997, 10, 250)) {
            buffer[0] = n.toDouble()
            val exact = buffer.copyOf(n + 3)
            assertEquals("n=$n", call(exact), call(buffer))
        }
    }

    @Test
    fun `the caller's profile is never modified`() {
        for (c in cases) {
            val before = c.profile.copyOf()
            c.run()
            assertArrayEquals(c.id, before, c.profile, 0.0)
        }
    }

    @Test
    fun `malformed profiles are rejected with IllegalArgumentException`() {
        val ok = doubleArrayOf(3.0, 100.0, 10.0, 11.0, 12.0, 13.0)
        call(ok) // sanity: exactly n + 3 entries works
        assertThrows(IllegalArgumentException::class.java) { call(ok.copyOf(5)) }
        assertThrows(IllegalArgumentException::class.java) { call(doubleArrayOf(0.0, 100.0, 10.0)) }
        assertThrows(IllegalArgumentException::class.java) { call(doubleArrayOf(3.0, 0.0, 10.0, 11.0, 12.0, 13.0)) }
        assertThrows(IllegalArgumentException::class.java) { call(doubleArrayOf(3.0)) }
    }

    @Test
    fun `invalid inputs raise NTIA's error code`() {
        val e = assertThrows(ItmException::class.java) {
            Itm.pointToPoint(
                0.4, 1.5, doubleArrayOf(3.0, 100.0, 10.0, 11.0, 12.0, 13.0), Climate.DESERT, 301.0, 146.0,
                Polarization.VERTICAL, 15.0, 0.005, VariabilityMode.BROADCAST, 50.0, 50.0, 50.0,
            )
        }
        assertEquals(ItmError.TX_TERMINAL_HEIGHT, e.code)
        val t = assertThrows(ItmException::class.java) {
            Itm.pointToPoint(
                10.0, 1.5, doubleArrayOf(3.0, 100.0, 10.0, 11.0, 12.0, 13.0), Climate.DESERT, 301.0, 146.0,
                Polarization.VERTICAL, 15.0, 0.005, VariabilityMode.BROADCAST, 100.0, 50.0, 50.0,
            )
        }
        assertEquals(ItmError.INVALID_TIME, t.code)
    }
}
