/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.channel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.ChirpCsv
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Power
import com.vyntric.offset.protocol.PttId
import com.vyntric.offset.protocol.RadioProfile
import com.vyntric.offset.protocol.Squelch
import com.vyntric.offset.protocol.ToneMode
import com.vyntric.offset.protocol.Tones
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.SegmentedRow
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.ToggleRow
import com.vyntric.offset.ui.theme.OffsetTheme

/** Editable text form of a channel. Converting back validates against the radio's limits. */
data class ChannelDraft(
    val name: String = "",
    val freq: String = "",
    val duplex: Duplex = Duplex.SIMPLEX,
    val offset: String = "0.6",
    val toneMode: ToneMode = ToneMode.NONE,
    val tx: Squelch = Squelch.Ctcss(885),
    val rx: Squelch = Squelch.Ctcss(885),
    val power: Power = Power.HIGH,
    val wide: Boolean = true,
    val scan: Boolean = true,
    val bcl: Boolean = false,
    val pttId: PttId = PttId.OFF,
    val pttIdCode: Int = 0,
) {
    data class Result(val channel: Channel?, val problems: Map<ChannelValidator.Field, String>)

    fun build(model: RadioProfile): Result {
        val problems = LinkedHashMap<ChannelValidator.Field, String>()
        val rxHz = ChirpCsv.parseFreq(freq)?.takeIf { it > 0 }
        if (rxHz == null) problems[ChannelValidator.Field.FREQUENCY] = "Enter a frequency in MHz, for example 146.520."
        val offHz = when (duplex) {
            Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT -> ChirpCsv.parseFreq(offset)?.takeIf { it > 0 }.also {
                if (it == null) problems[ChannelValidator.Field.OFFSET] = "Enter the ${if (duplex == Duplex.SPLIT) "transmit frequency" else "offset"} in MHz."
            }
            else -> 0L
        }
        if (rxHz == null || offHz == null) return Result(null, problems)
        val (txS, rxS) = when (toneMode) {
            ToneMode.NONE -> Squelch.None to Squelch.None
            ToneMode.TONE -> tx to Squelch.None
            ToneMode.TSQL -> tx to tx
            ToneMode.DTCS -> tx to tx
            ToneMode.CROSS -> tx to rx
        }
        val channel = Channel(
            rxFreqHz = rxHz, duplex = duplex, offsetHz = offHz, txSquelch = txS, rxSquelch = rxS, power = power,
            wide = wide, scanAdd = scan, busyLockout = bcl, pttId = pttId, pttIdCode = pttIdCode, name = name.trim().uppercase(),
        )
        ChannelValidator.validate(channel, model).forEach { p -> problems.putIfAbsent(p.field, p.message) }
        return Result(if (problems.isEmpty()) channel else null, problems)
    }

    companion object {
        fun from(c: Channel) = ChannelDraft(
            name = c.name,
            freq = Fmt.mhz(c.rxFreqHz),
            duplex = c.duplex,
            offset = when (c.duplex) {
                Duplex.SPLIT -> Fmt.mhz(c.offsetHz)
                Duplex.PLUS, Duplex.MINUS -> (c.offsetHz / 1_000_000.0).toString().trimEnd('0').trimEnd('.')
                else -> "0.6"
            },
            toneMode = c.toneMode,
            tx = c.txSquelch.takeIf { it != Squelch.None } ?: Squelch.Ctcss(885),
            rx = c.rxSquelch.takeIf { it != Squelch.None } ?: Squelch.Ctcss(885),
            power = c.power, wide = c.wide, scan = c.scanAdd, bcl = c.busyLockout, pttId = c.pttId, pttIdCode = c.pttIdCode,
        )
    }
}

private val CTCSS_OPTIONS: List<Squelch> = Tones.CTCSS_TENTHS.map { Squelch.Ctcss(it) }
private val DCS_OPTIONS: List<Squelch> = Tones.DCS_CODES.flatMap { listOf(Squelch.Dcs(it, false), Squelch.Dcs(it, true)) }
private val ANY_OPTIONS: List<Squelch> = listOf(Squelch.None) + CTCSS_OPTIONS + DCS_OPTIONS

/**
 * Bottom sheet that edits one channel within a radio profile's limits. [onDelete] adds a destructive row.
 */
@Composable
fun ChannelEditorSheet(
    title: String,
    initial: Channel?,
    model: RadioProfile,
    onSave: (Channel) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Delete channel",
) {
    var draft by remember(initial) { mutableStateOf(initial?.let { ChannelDraft.from(it) } ?: ChannelDraft()) }
    var touched by remember { mutableStateOf(initial != null) }
    val result = draft.build(model)
    val show = { f: ChannelValidator.Field -> if (touched) result.problems[f] else null }

    OffsetSheet(
        onDismiss = onDismiss,
        title = title,
        leadingAction = { BarAction("Cancel", onClick = onDismiss) },
        trailingAction = {
            BarAction("Save", enabled = result.channel != null) { result.channel?.let(onSave) }
        },
    ) {
        InsetSection(header = null) {
            cell {
                TextFieldRow(
                    "Name", draft.name, { touched = true; draft = draft.copy(name = (if (model.uppercaseNames) it.uppercase() else it).take(model.nameLength)) },
                    placeholder = "Up to ${model.nameLength} characters",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    error = show(ChannelValidator.Field.NAME),
                )
            }
            cell {
                TextFieldRow(
                    "Frequency", draft.freq, { touched = true; draft = draft.copy(freq = it.filter { c -> c.isDigit() || c == '.' }) },
                    placeholder = "146.520 MHz",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    error = show(ChannelValidator.Field.FREQUENCY),
                )
            }
        }
        InsetSection(header = "Offset") {
            cell {
                val duplexes = listOf(Duplex.SIMPLEX, Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT, Duplex.OFF)
                SegmentedRow(null, listOf("Simplex", "+", "-", "Split", "TX off"), duplexes.indexOf(draft.duplex)) {
                    touched = true
                    val d = duplexes[it]
                    draft = draft.copy(
                        duplex = d,
                        offset = when {
                            d == Duplex.SPLIT -> draft.freq
                            draft.duplex == Duplex.SPLIT -> if ((ChirpCsv.parseFreq(draft.freq) ?: 0) >= 400_000_000) "5" else "0.6"
                            else -> draft.offset
                        },
                    )
                }
            }
            if (draft.duplex in setOf(Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT)) {
                cell {
                    TextFieldRow(
                        if (draft.duplex == Duplex.SPLIT) "TX freq" else "Offset", draft.offset,
                        { touched = true; draft = draft.copy(offset = it.filter { c -> c.isDigit() || c == '.' }) },
                        placeholder = "MHz", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        error = show(ChannelValidator.Field.OFFSET) ?: show(ChannelValidator.Field.TX_FREQUENCY),
                    )
                }
            }
        }
        InsetSection(header = "Tone") {
            val modes = listOf(ToneMode.NONE, ToneMode.TONE, ToneMode.TSQL, ToneMode.DTCS, ToneMode.CROSS)
            cell {
                SegmentedRow(null, listOf("None", "Tone", "TSQL", "DCS", "Cross"), modes.indexOf(draft.toneMode)) {
                    val m = modes[it]
                    val tx = when (m) {
                        ToneMode.DTCS -> draft.tx as? Squelch.Dcs ?: Squelch.Dcs(23)
                        ToneMode.TONE, ToneMode.TSQL -> draft.tx as? Squelch.Ctcss ?: Squelch.Ctcss(885)
                        else -> draft.tx
                    }
                    draft = draft.copy(toneMode = m, tx = tx)
                }
            }
            when (draft.toneMode) {
                ToneMode.NONE -> Unit
                ToneMode.TONE, ToneMode.TSQL -> cell {
                    ChoiceRow("Tone", draft.tx, CTCSS_OPTIONS, Fmt::squelch) { draft = draft.copy(tx = it) }
                }
                ToneMode.DTCS -> cell {
                    ChoiceRow("Code", draft.tx, DCS_OPTIONS, Fmt::squelch) { draft = draft.copy(tx = it) }
                }
                ToneMode.CROSS -> {
                    cell { ChoiceRow("Transmit", draft.tx, ANY_OPTIONS, Fmt::squelch) { draft = draft.copy(tx = it) } }
                    cell { ChoiceRow("Receive", draft.rx, ANY_OPTIONS, Fmt::squelch) { draft = draft.copy(rx = it) } }
                }
            }
        }
        InsetSection(header = "Transmit", footer = show(ChannelValidator.Field.POWER)) {
            cell {
                val levels = model.powerLevels
                SegmentedRow("Power", levels.map { it.label }, levels.indexOf(draft.power)) { draft = draft.copy(power = levels[it]) }
            }
            cell { SegmentedRow("Bandwidth", listOf("Wide", "Narrow"), if (draft.wide) 0 else 1) { draft = draft.copy(wide = it == 0) } }
            cell { ToggleRow("Busy channel lockout", draft.bcl, { draft = draft.copy(bcl = it) }) }
            cell { ToggleRow("Scan add", draft.scan, { draft = draft.copy(scan = it) }) }
        }
        InsetSection(header = "PTT-ID", footer = "PTT-ID sends a DTMF code when you key up (BOT), unkey (EOT) or both.") {
            cell {
                SegmentedRow(null, PttId.entries.map { it.label }, draft.pttId.ordinal) { draft = draft.copy(pttId = PttId.entries[it]) }
            }
            cell { ChoiceRow("Code", draft.pttIdCode, (0..14).toList(), { "Code ${it + 1}" }) { draft = draft.copy(pttIdCode = it) } }
        }
        if (onDelete != null) {
            InsetSection { cell { ButtonRow(deleteLabel, destructive = true, onClick = onDelete) } }
        }
    }
}

/** A row that opens a menu of choices. */
@Composable
fun <T> ChoiceRow(label: String, value: T, options: List<T>, display: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CellRow(label, value = display(value), chevron = true, onClickLabel = "Choose $label") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OffsetTheme.colors.surfaceRaised) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option), style = OffsetTheme.type.body, color = OffsetTheme.colors.label) },
                    onClick = { open = false; onSelect(option) },
                )
            }
        }
    }
}
