/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelOption
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.ChirpCsv
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Modulation
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
import com.vyntric.offset.ui.components.SectionScope
import com.vyntric.offset.ui.components.SegmentedRow
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.ToggleRow
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.theme.Dimens
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
    val mode: Modulation = Modulation.FM,
    /**
     * Radio options the channel carries or the user set, as typed (a clarifier may read "-1.2"
     * while it is edited). Options never set stay out, so the radio keeps what the slot holds.
     */
    val options: Map<String, String> = emptyMap(),
    /** The offset in use before a receive only mode turned transmit off, restored on leaving it. */
    val heldDuplex: Duplex? = null,
) {
    /** [optionProblems] holds problems with radio options, by [ChannelOption.key]. */
    data class Result(
        val channel: Channel?,
        val problems: Map<ChannelValidator.Field, String>,
        val optionProblems: Map<String, String> = emptyMap(),
    )

    /** The value [option] shows: what the channel carries, or the radio's default. */
    fun value(option: ChannelOption): String = options[option.key] ?: option.default

    fun with(option: ChannelOption, value: String) = copy(options = options + (option.key to value))

    /**
     * This draft in [mode]: a receive only mode turns transmit off, leaving one turns it back on,
     * and a choice that does not apply to the new mode (an HF filter width on FM) moves to the
     * first one that does.
     */
    fun withMode(mode: Modulation, model: RadioProfile? = null): ChannelDraft {
        val moved = when {
            mode.receiveOnly -> copy(mode = mode, duplex = Duplex.OFF, heldDuplex = if (this.mode.receiveOnly) heldDuplex else duplex)
            this.mode.receiveOnly && duplex == Duplex.OFF ->
                copy(mode = mode, duplex = heldDuplex?.takeIf { it != Duplex.OFF } ?: Duplex.SIMPLEX, heldDuplex = null)
            else -> copy(mode = mode)
        }
        val fixes = model?.channelOptions.orEmpty().filterIsInstance<ChannelOption.Choice>().mapNotNull { option ->
            val fits = option.choicesFor(mode)
            if (fits.isEmpty() || fits.any { it.first == value(option) }) null else option.key to fits.first().first
        }
        return moved.copy(options = moved.options + fixes)
    }

    fun build(model: RadioProfile): Result {
        val problems = LinkedHashMap<ChannelValidator.Field, String>()
        val optionProblems = LinkedHashMap<String, String>()
        val rxHz = ChirpCsv.parseFreq(freq)?.takeIf { it > 0 }
        if (rxHz == null) problems[ChannelValidator.Field.FREQUENCY] = "Enter a frequency in MHz, for example 146.520."
        val offHz = when (duplex) {
            Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT -> ChirpCsv.parseFreq(offset)?.takeIf { it > 0 }.also {
                if (it == null) problems[ChannelValidator.Field.OFFSET] = "Enter the ${if (duplex == Duplex.SPLIT) "transmit frequency" else "offset"} in MHz."
            }
            else -> 0L
        }
        val stored = LinkedHashMap<String, String>()
        for (option in model.channelOptions) {
            val raw = options[option.key] ?: continue
            val value = if (option is ChannelOption.Number) option.canonical(raw) else raw
            if (value == null) optionProblems[option.key] = "Enter a number for ${option.label.lowercase()}." else stored[option.key] = value
        }
        if (rxHz == null || offHz == null || optionProblems.isNotEmpty()) return Result(null, problems, optionProblems)
        val (txS, rxS) = when (toneMode) {
            ToneMode.NONE -> Squelch.None to Squelch.None
            ToneMode.TONE -> tx to Squelch.None
            ToneMode.TSQL -> tx to tx
            ToneMode.DTCS -> tx to tx
            ToneMode.CROSS -> tx to rx
        }
        // A radio whose bandwidth is an option says narrow through it; CHIRP files still see wide or narrow.
        val bandwidth = model.channelOptions.firstOrNull { it.key.endsWith(".bandwidth") }
        val isWide = if (!model.storesWide && bandwidth != null) value(bandwidth) != "1" else wide
        val channel = Channel(
            rxFreqHz = rxHz, duplex = duplex, offsetHz = offHz, txSquelch = txS, rxSquelch = rxS, power = power,
            wide = isWide, scanAdd = scan, busyLockout = bcl, pttId = pttId, pttIdCode = pttIdCode,
            name = name.trim().let { if (model.uppercaseNames) it.uppercase() else it },
            mode = mode, options = stored,
        )
        ChannelValidator.validate(channel, model).forEach { p ->
            val key = p.optionKey
            if (key != null) optionProblems.putIfAbsent(key, p.message) else problems.putIfAbsent(p.field, p.message)
        }
        return Result(if (problems.isEmpty() && optionProblems.isEmpty()) channel else null, problems, optionProblems)
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
            mode = c.mode, options = c.options,
        )
    }
}

private val CTCSS_OPTIONS: List<Squelch> = Tones.CTCSS_TENTHS.map { Squelch.Ctcss(it) }
private val DCS_OPTIONS: List<Squelch> = Tones.DCS_CODES.flatMap { listOf(Squelch.Dcs(it, false), Squelch.Dcs(it, true)) }
private val ANY_OPTIONS: List<Squelch> = listOf(Squelch.None) + CTCSS_OPTIONS + DCS_OPTIONS

const val PRIVACY_WARNING =
    "Scramblers, encryption and frequency hopping hide what you say. US rules forbid that on amateur and " +
        "GMRS frequencies, and most countries' rules do too. Use them only where your licence allows."

/**
 * Bottom sheet that edits one channel within a radio profile's limits: the shared settings, then
 * every setting the radio declares, grouped as the radio's own programming software groups them.
 * A section appears only when the radio has something in it. [onDelete] adds a destructive row.
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
    /** The radio's zone names, to pick the channel's zone; [onSaveInZone] then receives the save. */
    zoneNames: List<String>? = null,
    initialZone: Int = 0,
    onSaveInZone: ((Channel, Int) -> Unit)? = null,
) {
    var zone by remember(initialZone) { mutableStateOf(initialZone) }
    var draft by remember(initial) { mutableStateOf(initial?.let { ChannelDraft.from(it) } ?: ChannelDraft()) }
    var touched by remember { mutableStateOf(initial != null) }
    val result = draft.build(model)
    val show = { f: ChannelValidator.Field -> if (touched) result.problems[f] else null }
    val edit = { d: ChannelDraft -> touched = true; draft = d }
    val options = model.channelOptions.groupBy { it.section }
    val receiveOnly = draft.mode.receiveOnly

    fun optionRows(scope: SectionScope, section: ChannelOption.Section) {
        options[section].orEmpty().forEach { option ->
            scope.cell {
                OptionRow(option, draft.value(option), draft.mode, result.optionProblems[option.key]) { edit(draft.with(option, it)) }
            }
        }
    }

    /** Help for a section: [lead], each option's help, then [trail]. */
    fun footer(section: ChannelOption.Section, lead: String? = null, trail: String? = null): String? =
        (listOf(lead) + options[section].orEmpty().map { it.help.ifEmpty { null } } + trail).filterNotNull().joinToString(" ").ifEmpty { null }

    OffsetSheet(
        onDismiss = onDismiss,
        title = title,
        leadingAction = { BarAction("Cancel", onClick = onDismiss) },
        trailingAction = {
            BarAction("Save", enabled = result.channel != null) {
                result.channel?.let { ch -> if (zoneNames != null && onSaveInZone != null) onSaveInZone(ch, zone) else onSave(ch) }
            }
        },
    ) {
        if (zoneNames != null && onSaveInZone != null) {
            InsetSection(header = null) {
                cell { ChoiceRow("Zone", zone, zoneNames.indices.toList(), { zoneLabel(it, zoneNames) }) { zone = it } }
            }
        }
        InsetSection(header = null) {
            cell {
                TextFieldRow(
                    "Name", draft.name, { edit(draft.copy(name = (if (model.uppercaseNames) it.uppercase() else it).take(model.nameLength))) },
                    placeholder = "Up to ${model.nameLength} characters",
                    keyboardOptions = KeyboardOptions(capitalization = if (model.uppercaseNames) KeyboardCapitalization.Characters else KeyboardCapitalization.Words),
                    error = show(ChannelValidator.Field.NAME),
                )
            }
            cell {
                TextFieldRow(
                    "Frequency", draft.freq, { edit(draft.copy(freq = it.filter { c -> c.isDigit() || c == '.' })) },
                    placeholder = if (model.fineTuning) "146.520 or 7.074 MHz" else "146.520 MHz",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    error = show(ChannelValidator.Field.FREQUENCY),
                )
            }
        }

        val modeOptions = options[ChannelOption.Section.MODE].orEmpty()
        if (model.modes.size > 1 || model.storesWide || modeOptions.isNotEmpty()) {
            InsetSection(
                header = "Mode",
                footer = show(ChannelValidator.Field.MODE) ?: footer(
                    ChannelOption.Section.MODE,
                    if (receiveOnly) "${draft.mode.label} is received with the radio's HF receiver, so this channel only listens." else null,
                ),
            ) {
                if (model.modes.size in 2..4) {
                    cell { SegmentedRow(null, model.modes.map { it.label }, model.modes.indexOf(draft.mode)) { edit(draft.withMode(model.modes[it], model)) } }
                } else if (model.modes.size > 4) {
                    cell { ChoiceRow("Mode", draft.mode, model.modes, { it.label }) { edit(draft.withMode(it, model)) } }
                }
                if (model.storesWide) {
                    cell { SegmentedRow("Bandwidth", listOf("Wide", "Narrow"), if (draft.wide) 0 else 1) { edit(draft.copy(wide = it == 0)) } }
                }
                optionRows(this, ChannelOption.Section.MODE)
            }
        }

        if (!receiveOnly) {
            InsetSection(header = "Offset") {
                cell {
                    val duplexes = listOf(Duplex.SIMPLEX, Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT, Duplex.OFF)
                    SegmentedRow(null, listOf("Simplex", "+", "-", "Split", "TX off"), duplexes.indexOf(draft.duplex)) {
                        val d = duplexes[it]
                        edit(
                            draft.copy(
                                duplex = d,
                                offset = when {
                                    d == Duplex.SPLIT -> draft.freq
                                    draft.duplex == Duplex.SPLIT -> if ((ChirpCsv.parseFreq(draft.freq) ?: 0) >= 400_000_000) "5" else "0.6"
                                    else -> draft.offset
                                },
                            ),
                        )
                    }
                }
                if (draft.duplex in setOf(Duplex.PLUS, Duplex.MINUS, Duplex.SPLIT)) {
                    cell {
                        TextFieldRow(
                            if (draft.duplex == Duplex.SPLIT) "TX freq" else "Offset", draft.offset,
                            { edit(draft.copy(offset = it.filter { c -> c.isDigit() || c == '.' })) },
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
            InsetSection(
                header = "Transmit",
                footer = show(ChannelValidator.Field.POWER)
                    ?: if (draft.power == Power.ZERO) "Zero keys the transmitter with almost no output, for testing PTT-ID and roger beeps." else null,
            ) {
                cell {
                    val levels = model.powerLevels
                    if (levels.size <= 3) {
                        SegmentedRow("Power", levels.map { it.label }, levels.indexOf(draft.power)) { draft = draft.copy(power = levels[it]) }
                    } else {
                        ChoiceRow("Power", draft.power, levels, { it.label }) { draft = draft.copy(power = it) }
                    }
                }
                cell { ToggleRow("Busy channel lockout", draft.bcl, { draft = draft.copy(bcl = it) }) }
            }
        }

        val scanOptions = options[ChannelOption.Section.SCAN].orEmpty()
        if (model.storesScanAdd || scanOptions.isNotEmpty()) {
            InsetSection(header = "Scan", footer = footer(ChannelOption.Section.SCAN)) {
                if (model.storesScanAdd) cell { ToggleRow("Scan add", draft.scan, { draft = draft.copy(scan = it) }) }
                optionRows(this, ChannelOption.Section.SCAN)
            }
        }

        if (!receiveOnly) {
            InsetSection(
                header = "Signalling",
                footer = footer(ChannelOption.Section.SIGNALLING, "PTT-ID sends a DTMF code when you key up (BOT), unkey (EOT) or both."),
            ) {
                cell {
                    SegmentedRow("PTT-ID", PttId.entries.map { it.label }, draft.pttId.ordinal) { draft = draft.copy(pttId = PttId.entries[it]) }
                }
                if (model.pttIdCodes > 0) {
                    cell { ChoiceRow("Code", draft.pttIdCode, (0 until model.pttIdCodes).toList(), { "Code ${it + 1}" }) { draft = draft.copy(pttIdCode = it) } }
                }
                optionRows(this, ChannelOption.Section.SIGNALLING)
            }
        }

        if (options[ChannelOption.Section.PRIVACY].orEmpty().isNotEmpty()) {
            InsetSection(header = "Privacy", footer = footer(ChannelOption.Section.PRIVACY, trail = PRIVACY_WARNING)) {
                optionRows(this, ChannelOption.Section.PRIVACY)
            }
        }

        if (options[ChannelOption.Section.ADVANCED].orEmpty().isNotEmpty()) {
            InsetSection(header = "Advanced", footer = footer(ChannelOption.Section.ADVANCED)) {
                optionRows(this, ChannelOption.Section.ADVANCED)
            }
        }

        if (onDelete != null) {
            InsetSection { cell { ButtonRow(deleteLabel, destructive = true, onClick = onDelete) } }
        }
    }
}

/** "3 · CB": a zone's number and name. */
fun zoneLabel(zone: Int, names: List<String>): String = "${zone + 1} · ${names.getOrElse(zone) { "" }}".trimEnd(' ', '·')

/** One radio declared option, drawn as the control that suits its kind. */
@Composable
private fun OptionRow(option: ChannelOption, value: String, mode: Modulation, error: String?, onChange: (String) -> Unit) {
    when (option) {
        is ChannelOption.Choice -> {
            val choices = option.choicesFor(mode)
            if (choices.size <= 4 && choices.any { it.first == value }) {
                SegmentedRow(option.label, choices.map { it.second }, choices.indexOfFirst { it.first == value }) {
                    onChange(choices[it].first)
                }
            } else {
                // The radio may hold a value its own menus never offer; it stays listed so it can be kept.
                val values = choices.map { it.first }.let { if (value in it) it else listOf(value) + it }
                ChoiceRow(option.label, value, values, option::display, onChange)
            }
        }
        is ChannelOption.Toggle -> ToggleRow(option.label, value == "true", { onChange(it.toString()) })
        is ChannelOption.Letters -> LetterPicker(option, value, error, onChange)
        is ChannelOption.Number -> TextFieldRow(
            option.label, value, { onChange(it.filter { c -> c.isDigit() || c == '.' || c == '-' || c == ',' }) },
            placeholder = "0.00 ${option.unit}",
            labelWidth = 150.dp,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            error = error,
        )
        is ChannelOption.HexCode -> TextFieldRow(
            option.label, value, { onChange(it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(option.length)) },
            placeholder = "${option.length} hex digits",
            labelWidth = 150.dp,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            error = error,
        )
    }
}

/**
 * The letters of [option] as a grid of round keys. Picked letters fill with the accent; once
 * [ChannelOption.Letters.max] are picked the others dim until one is let go.
 */
@Composable
private fun LetterPicker(option: ChannelOption.Letters, value: String, error: String?, onChange: (String) -> Unit) {
    val colors = OffsetTheme.colors
    val full = value.length >= option.max
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(option.label, style = OffsetTheme.type.body, color = colors.label, modifier = Modifier.weight(1f))
            Text(
                if (value.isEmpty()) "None" else "${option.display(value)}  ·  ${value.length} of ${option.max}",
                style = OffsetTheme.type.subhead, color = colors.secondaryLabel,
            )
        }
        // Seven even columns: A to Z fills four rows edge to edge, the last one left aligned.
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            option.alphabet.chunked(LETTER_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { letter ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LetterKey(letter, letter in value, letter in value || !full) { onChange(option.toggle(value, letter)) } }
                    }
                    repeat(LETTER_COLUMNS - row.length) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (error != null) Text(error, style = OffsetTheme.type.footnote, color = colors.destructive, modifier = Modifier.padding(top = 6.dp))
    }
}

private const val LETTER_COLUMNS = 7

/** One round key of a [LetterPicker]: accent filled when [picked], dimmed when not [enabled]. */
@Composable
private fun LetterKey(letter: Char, picked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = OffsetTheme.colors
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (picked) colors.accent else colors.fill)
            .selectable(selected = picked, enabled = enabled, role = Role.Checkbox) {
                haptics.select()
                onToggle()
            }
            .semantics { contentDescription = "Group $letter" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.toString(),
            style = OffsetTheme.type.callout.copy(fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium),
            color = when {
                picked -> colors.onAccent
                enabled -> colors.label
                else -> colors.tertiaryLabel
            },
        )
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
