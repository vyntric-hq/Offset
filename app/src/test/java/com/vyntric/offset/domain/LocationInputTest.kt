package com.vyntric.offset.domain

import com.vyntric.offset.data.CountyDirectory
import com.vyntric.offset.ui.repeaters.LocationMode
import com.vyntric.offset.ui.repeaters.RepeatersUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocationInputTest {
    @Test fun `grid squares match known references`() {
        // ARRL headquarters, W1AW, Newington CT is in FN31pr.
        assertEquals("FN31pr", Maidenhead.fromLatLon(41.714775, -72.727260))
        assertEquals("FN31", Maidenhead.fromLatLon(41.714775, -72.727260, 4))
        // Louisville, KY.
        assertEquals("EM78", Maidenhead.fromLatLon(38.2527, -85.7585, 4))
        assertEquals("JJ00aa", Maidenhead.fromLatLon(0.0, 0.0))
    }

    @Test fun `grid squares convert back to the centre of the square`() {
        val (lat, lon) = Maidenhead.toLatLon("FN31pr")!!
        assertEquals("FN31pr", Maidenhead.fromLatLon(lat, lon))
        assertEquals(41.7292, lat, 0.001)
        assertEquals(-72.7083, lon, 0.001)
        assertEquals(Maidenhead.toLatLon("FN31PR"), Maidenhead.toLatLon("fn31pr"))
        val four = Maidenhead.toLatLon("EM78")!!
        assertEquals(38.5, four.first, 1e-9)
        assertEquals(-85.0, four.second, 1e-9)
        for (bad in listOf("", "E", "ZZ00", "EM7", "EMAB", "EM78zz", "EM78gh4")) assertNull(bad, Maidenhead.toLatLon(bad))
    }

    @Test fun `typed coordinates accept signs and hemisphere letters`() {
        assertEquals(38.25 to -85.76, Maidenhead.parseLatLon("38.25, -85.76"))
        assertEquals(38.25 to -85.76, Maidenhead.parseLatLon("38.25 -85.76"))
        assertEquals(38.25 to -85.76, Maidenhead.parseLatLon("38.25N 85.76W"))
        assertEquals(-33.9 to 151.2, Maidenhead.parseLatLon("33.9S, 151.2E"))
        assertNull(Maidenhead.parseLatLon("95, 10"))
        assertNull(Maidenhead.parseLatLon("38.25"))
        assertNull(Maidenhead.parseLatLon("north, west"))
    }

    @Test fun `the shipped county list covers every state`() {
        val file = File("src/main/assets/places/counties.tsv")
        val all = CountyDirectory.parse(file.readText())
        assertTrue("${all.size} counties", all.size > 3_100)
        val dir = CountyDirectory { file.inputStream() }
        val jefferson = dir.inState("KY").first { it.name == "Jefferson County" }
        assertEquals(38.19, jefferson.latitude, 0.05)
        assertEquals(-85.66, jefferson.longitude, 0.05)
        for (state in UsState.entries) assertTrue("no counties for ${state.abbr}", dir.inState(state.abbr).isNotEmpty())
    }

    @Test fun `the search centre follows the chosen source`() {
        val county = County("KY", "Jefferson County", 38.19, -85.66)
        val base = RepeatersUiState()
        assertNull(base.center)
        assertEquals("EM78", base.copy(locationMode = LocationMode.GRID, gridText = "em78").center!!.label)
        assertEquals(38.25, base.copy(locationMode = LocationMode.LATLON, latLonText = "38.25,-85.76").center!!.latitude, 1e-9)
        val c = base.copy(locationMode = LocationMode.COUNTY, countyPick = county).center!!
        assertEquals("Jefferson County", c.label)
        assertEquals("EM78", c.grid.take(4))
    }
}
