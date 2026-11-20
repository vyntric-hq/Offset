/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.vyntric.offset.data.ClockFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Dates and times as the user chose in Settings: a 12 or 24 hour clock, in the phone's time
 * zone or a fixed one. A fixed zone is named after the time ("2:05 PM UTC") so it is never
 * mistaken for local time.
 */
class TimeDisplay(
    private val twentyFourHour: Boolean,
    /** Null follows the phone's zone, read at each call so a zone change shows at once. */
    private val zone: ZoneId?,
    private val locale: Locale = Locale.getDefault(),
) {
    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val timeFmt = DateTimeFormatter.ofPattern(
        (if (twentyFourHour) "HH:mm" else "h:mm a") + if (zone != null) " z" else "", locale,
    )

    private fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone ?: ZoneId.systemDefault())

    fun date(millis: Long): String = dateFmt.format(at(millis))

    fun time(millis: Long): String = timeFmt.format(at(millis))

    fun dateTime(millis: Long): String = "${date(millis)}, ${time(millis)}"

    companion object {
        fun of(format: ClockFormat, phoneIs24Hour: Boolean, zoneId: String?): TimeDisplay = TimeDisplay(
            twentyFourHour = when (format) {
                ClockFormat.PHONE -> phoneIs24Hour
                ClockFormat.TWELVE_HOUR -> false
                ClockFormat.TWENTY_FOUR_HOUR -> true
            },
            zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() },
        )
    }
}

val LocalTimeDisplay = staticCompositionLocalOf { TimeDisplay(twentyFourHour = false, zone = null) }
