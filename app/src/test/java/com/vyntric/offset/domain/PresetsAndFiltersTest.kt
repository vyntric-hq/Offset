package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Band
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Squelch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsAndFiltersTest {
    @Test fun `every preset channel is valid on both radio profiles`() {
        for (preset in SimplexPresets.ALL) for (model in RadioModel.entries) for (ch in preset.channels) {
            val problems = ChannelValidator.validate(ch, model).filter { it.field != ChannelValidator.Field.POWER || model.triPower }
            assertTrue("${preset.id} ${ch.name} on $model: $problems", problems.isEmpty())
        }
    }

    @Test fun `GMRS presets follow the channel plan`() {
        val frs = SimplexPresets.FRS_GMRS.channels
        assertEquals(22, frs.size)
        assertEquals(462_562_500, frs[0].rxFreqHz)
        assertEquals(467_712_500, frs[13].rxFreqHz)
        assertTrue(frs.subList(7, 14).all { !it.wide })
        assertEquals(462_725_000, frs[21].rxFreqHz)
        val rpt = SimplexPresets.GMRS_REPEATERS.channels
        assertEquals(467_550_000L, rpt[0].txFreqHz)
        assertTrue(SimplexPresets.FRS_GMRS.includesGmrs)
        assertFalse(SimplexPresets.CALLING.includesGmrs)
        assertTrue(SimplexPresets.NOAA.channels.all { it.txFreqHz == null })
    }

    @Test fun `GMRS detection covers channels and repeater inputs only`() {
        assertTrue(Gmrs.isGmrs(462_562_500))
        assertTrue(Gmrs.isGmrs(467_725_000))
        assertFalse(Gmrs.isGmrs(462_000_000))
        assertFalse(Gmrs.isGmrs(446_000_000))
    }

    private fun r(call: String, hz: Long, mixed: Boolean = false, lat: Double = 37.0, lon: Double = -122.0) =
        Repeater(call, call, hz, hz, Squelch.None, Squelch.None, "Town", null, "CA", lat, lon, RepeaterSource.CSV, mixedMode = mixed)

    @Test fun `filters combine service, band and mixed mode`() {
        val all = listOf(r("A", 146_940_000), r("B", 442_100_000, mixed = true), r("G", 462_675_000), r("X", 223_500_000))
        fun run(f: RepeaterFilters) = RepeaterFilter.apply(all, RepeaterQuery.All(f, null)).map { it.callsign }.toSet()
        assertEquals(setOf("A", "B"), run(RepeaterFilters()))
        assertEquals(setOf("G"), run(RepeaterFilters(services = setOf(Service.GMRS))))
        assertEquals(setOf("A", "G"), run(RepeaterFilters(services = Service.entries.toSet(), hideMixedMode = true)))
        assertEquals(setOf("A", "G"), run(RepeaterFilters(services = Service.entries.toSet(), bands = setOf(Band.TWO_METER))))
    }

    @Test fun `closest limit ignores distance and keeps order`() {
        val list = (1..10).map { r("C$it", 146_000_000L + it * 15_000L, lat = 37.0 + it * 0.5) }
        val got = RepeaterFilter.apply(list, RepeaterQuery.Near(37.0, -122.0, null, RepeaterFilters(), limit = 3))
        assertEquals(listOf("C1", "C2", "C3"), got.map { it.callsign })
    }

    @Test fun `text matches callsign, place and frequency words`() {
        val x = r("K6ABC", 145_230_000)
        assertTrue(RepeaterFilter.matches(x, "k6abc"))
        assertTrue(RepeaterFilter.matches(x, "town california"))
        assertTrue(RepeaterFilter.matches(x, "145.23"))
        assertTrue(RepeaterFilter.matches(x, "14523"))
        assertFalse(RepeaterFilter.matches(x, "146.94"))
        assertFalse(RepeaterFilter.matches(x, ""))
    }
}
