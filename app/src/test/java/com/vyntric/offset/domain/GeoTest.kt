package com.vyntric.offset.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
    @Test fun `distance and bearing match known values`() {
        // San Francisco to Los Angeles: about 559 km, heading south east.
        val d = Geo.distanceKm(37.7749, -122.4194, 34.0522, -118.2437)
        assertEquals(559.0, d, 3.0)
        val b = Geo.bearingDeg(37.7749, -122.4194, 34.0522, -118.2437)
        assertEquals(136.5, b, 1.0)
        assertEquals("SE", Geo.compass(b))
        assertEquals("N", Geo.compass(359.0))
    }

    @Test fun `state parsing handles the formats directories use`() {
        assertEquals(UsState.TX, UsState.fromLocationText("Allen, TX"))
        assertEquals(UsState.CA, UsState.fromLocationText("Merced, California"))
        assertEquals(UsState.CO, UsState.fromLocationText("Parker, CO USA"))
        assertEquals(UsState.OR, UsState.fromLocationText("Beavercreek, Wilsonville, Metro Portland, OR USA"))
        assertEquals(UsState.TX, UsState.fromLocationText("Edinburg TX"))
        assertEquals(UsState.NM, UsState.fromLocationText("Las Cruces, New Mexico"))
        assertNull(UsState.fromLocationText("Vancouver, BC Canada"))
        assertNull(UsState.fromLocationText("Lodz, Poland, lodzkie"))
        assertNull(UsState.fromLocationText(null))
    }

    @Test fun `state boxes decide reachability for near me`() {
        // Texarkana sits on the TX and AR line; 50 km reaches both, not California.
        val reach = UsState.entries.filter { it.reachableFrom(33.44, -94.05, 50.0) }.toSet()
        assertEquals(true, UsState.TX in reach && UsState.AR in reach)
        assertEquals(false, UsState.CA in reach)
    }
}
