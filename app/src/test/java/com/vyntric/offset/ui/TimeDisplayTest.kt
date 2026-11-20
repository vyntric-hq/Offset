package com.vyntric.offset.ui

import com.vyntric.offset.data.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class TimeDisplayTest {
    /** 2026-11-20 19:05 UTC. */
    private val at = 1_795_201_500_000L

    @Test fun `a fixed zone is named after the time, on a 12 hour clock`() {
        val t = TimeDisplay(twentyFourHour = false, zone = ZoneId.of("UTC"), locale = Locale.US)
        assertEquals("Nov 20, 2026, 7:05 PM UTC", t.dateTime(at))
    }

    @Test fun `24 hour clock in another zone`() {
        val t = TimeDisplay(twentyFourHour = true, zone = ZoneId.of("America/Chicago"), locale = Locale.US)
        assertEquals("13:05 CST", t.time(at))
    }

    @Test fun `phone format follows the phone's clock setting, and an unknown zone falls back to the phone's`() {
        val t = TimeDisplay.of(ClockFormat.PHONE, phoneIs24Hour = true, zoneId = "Not/AZone")
        assertEquals(5, t.time(at).length) // "HH:mm" with no zone name
    }
}
