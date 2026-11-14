/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * Column semantics ported from CHIRP (chirp/drivers/generic_csv.py and Memory.to_csv), GPL.
 */
package com.vyntric.offset.protocol

import java.util.Locale

/**
 * CHIRP's generic CSV format.
 *
 * Export writes exactly CHIRP's columns. Import is tolerant: columns may be in any order, and
 * extra columns that coordinator lists often carry (Callsign, Latitude, Longitude, City, State,
 * County) are kept on the row so repeater lists can be searched by distance.
 */
object ChirpCsv {
    val HEADER = listOf(
        "Location", "Name", "Frequency", "Duplex", "Offset", "Tone", "rToneFreq", "cToneFreq",
        "DtcsCode", "DtcsPolarity", "RxDtcsCode", "CrossMode", "Mode", "TStep", "Skip", "Power",
        "Comment", "URCALL", "RPT1CALL", "RPT2CALL", "DVCODE",
    )

    data class Row(
        val location: Int,
        val channel: Channel,
        val comment: String = "",
        val extras: Map<String, String> = emptyMap(),
    )

    data class ParseResult(val rows: List<Row>, val errors: List<String>)

    fun write(channels: List<Pair<Int, Channel>>, model: RadioProfile): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for ((location, ch) in channels) {
            val tx = ch.txSquelch
            val rx = ch.rxSquelch
            val mode = ch.toneMode
            val rtone = (tx as? Squelch.Ctcss)?.tenths ?: 885
            val ctone = (rx as? Squelch.Ctcss)?.tenths ?: 885
            val dtcs = (tx as? Squelch.Dcs)?.code ?: (rx as? Squelch.Dcs)?.code ?: 23
            val rxDtcs = (rx as? Squelch.Dcs)?.code ?: dtcs
            val pol = "" + (if ((tx as? Squelch.Dcs)?.inverted == true) 'R' else 'N') +
                (if ((rx as? Squelch.Dcs)?.inverted == true) 'R' else 'N')
            val cross = if (mode == ToneMode.CROSS) crossMode(tx, rx) else "Tone->Tone"
            val cells = listOf(
                location.toString(),
                ch.name,
                formatFreq(ch.rxFreqHz),
                ch.duplex.chirp,
                formatFreq(ch.offsetHz),
                mode.chirp,
                Tones.formatCtcss(rtone),
                Tones.formatCtcss(ctone),
                Tones.formatDcs(dtcs),
                pol,
                Tones.formatDcs(rxDtcs),
                cross,
                ch.digitalMode ?: if (ch.wide) "FM" else "NFM",
                "5.00",
                if (ch.scanAdd) "" else "S",
                String.format(Locale.US, "%.1fW", model.watts(ch.power)),
                "",
                "", "", "", "",
            )
            sb.append(cells.joinToString(",") { quote(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun parse(text: String, model: RadioProfile = RadioModel.UV5R): ParseResult {
        val lines = splitRecords(text.removePrefix("﻿"))
        if (lines.isEmpty()) return ParseResult(emptyList(), listOf("The file is empty."))
        val header = lines.first().map { it.trim() }
        val col = header.withIndex().associate { (i, h) -> h.lowercase() to i }
        if ("frequency" !in col) return ParseResult(emptyList(), listOf("No Frequency column; this is not a CHIRP CSV."))
        val rows = ArrayList<Row>()
        val errors = ArrayList<String>()
        lines.drop(1).forEachIndexed { i, cells ->
            if (cells.all { it.isBlank() }) return@forEachIndexed
            fun get(name: String): String = col[name.lowercase()]?.let { cells.getOrNull(it) }?.trim().orEmpty()
            try {
                val freq = parseFreq(get("Frequency")) ?: throw IllegalArgumentException("bad frequency")
                if (freq == 0L) return@forEachIndexed
                val mode = get("Mode")
                if (mode.isNotEmpty() && mode.uppercase() !in ANALOG_MODES) {
                    throw IllegalArgumentException("$mode channels are not imported; Offset programs analog FM channels only")
                }
                val duplex = Duplex.fromChirp(get("Duplex"))
                val offset = parseFreq(get("Offset")) ?: 0L
                val (tx, rx) = squelchFrom(
                    get("Tone"), get("rToneFreq"), get("cToneFreq"), get("DtcsCode"),
                    get("DtcsPolarity"), get("RxDtcsCode"), get("CrossMode"),
                )
                val channel = Channel(
                    rxFreqHz = freq,
                    duplex = duplex,
                    offsetHz = offset,
                    txSquelch = tx,
                    rxSquelch = rx,
                    power = parsePower(get("Power"), model),
                    wide = !get("Mode").equals("NFM", ignoreCase = true),
                    scanAdd = !get("Skip").equals("S", ignoreCase = true),
                    name = get("Name"),
                )
                val known = HEADER.map { it.lowercase() }.toSet()
                val extras = header.withIndex()
                    .filter { (_, h) -> h.lowercase() !in known && h.isNotBlank() }
                    .associate { (idx, h) -> h to (cells.getOrNull(idx)?.trim().orEmpty()) }
                rows += Row(get("Location").toIntOrNull() ?: rows.size, channel, get("Comment"), extras)
            } catch (e: IllegalArgumentException) {
                errors += "Line ${i + 2}: ${e.message}"
            }
        }
        return ParseResult(rows, errors)
    }

    private fun squelchFrom(
        mode: String, rtone: String, ctone: String, dtcs: String, pol: String, rxDtcs: String, cross: String,
    ): Pair<Squelch, Squelch> {
        val p = pol.uppercase().padEnd(2, 'N')
        fun ctcss(v: String) = Squelch.Ctcss(Tones.nearestCtcss(((v.toDoubleOrNull() ?: 88.5) * 10).toInt()))
        fun dcs(v: String, inv: Char) = Squelch.Dcs(v.toIntOrNull() ?: 23, inv == 'R')
        return when (mode.lowercase()) {
            "" -> Squelch.None to Squelch.None
            "tone" -> ctcss(rtone) to Squelch.None
            "tsql" -> ctcss(ctone) to ctcss(ctone)
            "dtcs" -> dcs(dtcs, p[0]) to dcs(dtcs, p[1])
            "cross" -> {
                val (t, r) = cross.split("->", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val txs = when (t.lowercase()) { "tone" -> ctcss(rtone); "dtcs" -> dcs(dtcs, p[0]); else -> Squelch.None }
                val rxs = when (r.lowercase()) { "tone" -> ctcss(ctone); "dtcs" -> dcs(rxDtcs, p[1]); else -> Squelch.None }
                txs to rxs
            }
            else -> throw IllegalArgumentException("unknown tone mode \"$mode\"")
        }
    }

    private fun crossMode(tx: Squelch, rx: Squelch): String {
        fun n(s: Squelch) = when (s) { Squelch.None -> ""; is Squelch.Ctcss -> "Tone"; is Squelch.Dcs -> "DTCS" }
        return "${n(tx)}->${n(rx)}"
    }

    private fun parsePower(value: String, model: RadioProfile): Power {
        val v = value.trim()
        Power.entries.firstOrNull { it.label.equals(v, true) || it.name.equals(v, true) }?.let { return model.nearestPower(it) }
        if (v.equals("Mid", true) || v.equals("Middle", true)) return model.nearestPower(Power.MID)
        val watts = Regex("^([0-9.]+)\\s*[Ww]?$").find(v)?.groupValues?.get(1)?.toDoubleOrNull() ?: return Power.HIGH
        // The level with the nearest nominal wattage; a tie goes to the higher level.
        return model.powerLevels.minWith(compareBy<Power>({ kotlin.math.abs(model.watts(it) - watts) }, { -model.watts(it) }))
    }

    /** CHIRP modes Offset imports as analog channels. */
    private val ANALOG_MODES = setOf("FM", "NFM")

    /** CHIRP's format_freq: "%i.%06i" of MHz. */
    fun formatFreq(hz: Long): String = String.format(Locale.US, "%d.%06d", hz / 1_000_000, hz % 1_000_000)

    /** Parses "146.520000", "146.52", "146.52 MHz" or "600 kHz" into hertz. */
    fun parseFreq(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return 0
        if (v.endsWith(" kHz", true)) return v.dropLast(4).trim().toBigDecimalOrNull()?.movePointRight(3)?.toLong()
        val mhz = v.removeSuffix(" MHz").removeSuffix(" mhz").trim()
        return mhz.toBigDecimalOrNull()?.movePointRight(6)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** RFC 4180 style splitting with quoted fields. */
    internal fun splitRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var field = StringBuilder()
        var record = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { record += field.toString(); field = StringBuilder() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    record += field.toString(); field = StringBuilder()
                    records += record; record = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) { record += field.toString(); records += record }
        return records.filter { r -> r.any { it.isNotBlank() } }
    }
}
