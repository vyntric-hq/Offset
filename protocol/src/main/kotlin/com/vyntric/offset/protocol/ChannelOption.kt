/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import java.util.Locale

/**
 * A channel setting only some radios store, declared by the radio's [RadioProfile]. The editor
 * draws every declared option, the codec reads and writes it, and the validator checks it. Values
 * travel in [Channel.options] as strings, in the canonical form [check] accepts.
 */
sealed interface ChannelOption {
    /** Stable key, `radio.field`, persisted with staged channels. Never rename one. */
    val key: String
    val label: String
    val section: Section
    /** One or two sentences shown under the section; empty for none. */
    val help: String

    /** The value an empty slot holds, as the radio's own tools create a channel. */
    val default: String

    /** Null when [value] is valid, otherwise why not. */
    fun check(value: String): String?

    /** [value] as shown to the user. */
    fun display(value: String): String

    /** Editor sections, in the order they appear after the shared ones. */
    enum class Section(val title: String) {
        MODE("Mode"),
        SCAN("Scan"),
        SIGNALLING("Signalling"),
        PRIVACY("Privacy"),
        ADVANCED("Advanced"),
    }

    /** One of a fixed list. [choices] pairs each stored value with its label. */
    data class Choice(
        override val key: String,
        override val label: String,
        override val section: Section,
        val choices: List<Pair<String, String>>,
        override val default: String = choices.first().first,
        override val help: String = "",
        /**
         * Every value the field can hold, a superset of [choices]. A radio may hold a value its own
         * menus never offer; it is kept and shown as "Setting n", never flagged as a problem.
         */
        val storable: Set<String> = choices.map { it.first }.toSet(),
        /**
         * Values that only apply to receive only modes (the HF receiver's filter widths). When
         * set, those modes are offered just these, and every other mode the rest.
         */
        val receiveOnlyValues: Set<String> = emptySet(),
    ) : ChannelOption {
        /** The [choices] that make sense for a channel in [mode]. */
        fun choicesFor(mode: Modulation): List<Pair<String, String>> = when {
            receiveOnlyValues.isEmpty() -> choices
            mode.receiveOnly -> choices.filter { it.first in receiveOnlyValues }
            else -> choices.filter { it.first !in receiveOnlyValues }
        }

        override fun check(value: String) =
            if (value in storable || choices.any { it.first == value }) null else "$label has no setting \"$value\"."

        override fun display(value: String) = choices.firstOrNull { it.first == value }?.second ?: "Setting $value"
    }

    /** On or off, stored as "true" or "false". */
    data class Toggle(
        override val key: String,
        override val label: String,
        override val section: Section,
        override val default: String = "false",
        override val help: String = "",
    ) : ChannelOption {
        override fun check(value: String) = if (value == "true" || value == "false") null else "$label must be on or off."
        override fun display(value: String) = if (value == "true") "On" else "Off"
    }

    /**
     * A set of letters from [alphabet], at most [max] of them, stored sorted without repeats
     * ("ACZ"); empty for none.
     */
    data class Letters(
        override val key: String,
        override val label: String,
        override val section: Section,
        val max: Int,
        val alphabet: String = ('A'..'Z').joinToString(""),
        override val default: String = "",
        override val help: String = "",
    ) : ChannelOption {
        override fun check(value: String): String? = when {
            value.any { it !in alphabet } -> "$label may only use ${alphabet.first()} to ${alphabet.last()}."
            value.toSet().size != value.length || value.toList() != value.toList().sorted() -> "$label must be sorted, without repeats."
            value.length > max -> "A channel can be in at most $max ${label.lowercase()}."
            else -> null
        }

        override fun display(value: String) = if (value.isEmpty()) "None" else value.toList().joinToString(" ")

        /** [current] with [letter] added or removed, in canonical form. */
        fun toggle(current: String, letter: Char): String =
            (if (letter in current) current.toSet() - letter else current.toSet() + letter).sorted().joinToString("")
    }

    /**
     * A signed decimal between [min] and [max] with [decimals] places, stored as plain text
     * ("-1.25"), shown with [unit].
     */
    data class Number(
        override val key: String,
        override val label: String,
        override val section: Section,
        val min: Double,
        val max: Double,
        val decimals: Int,
        val unit: String,
        override val default: String = format(0.0, decimals),
        override val help: String = "",
    ) : ChannelOption {
        override fun check(value: String): String? {
            val v = value.toDoubleOrNull() ?: return "Enter a number for $label."
            if (v < min || v > max) return "$label must be between ${format(min, decimals)} and ${format(max, decimals)} $unit."
            return null
        }

        override fun display(value: String): String {
            val v = value.toDoubleOrNull() ?: return value
            return (if (v > 0) "+" else "") + format(v, decimals) + " " + unit
        }

        /** [text] as typed, in canonical form, or null when it is not a number. */
        fun canonical(text: String): String? = text.trim().replace(',', '.').toDoubleOrNull()?.let { format(it, decimals) }

        companion object {
            fun format(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
        }
    }

    /** Exactly [length] hex digits (upper case), or empty for none. */
    data class HexCode(
        override val key: String,
        override val label: String,
        override val section: Section,
        val length: Int,
        override val default: String = "",
        override val help: String = "",
    ) : ChannelOption {
        override fun check(value: String): String? =
            if (value.isEmpty() || (value.length == length && value.all { it in HEX })) null
            else "$label is $length hex digits (0 to 9, A to F), or empty for none."

        override fun display(value: String) = value.ifEmpty { "None" }

        private companion object {
            const val HEX = "0123456789ABCDEF"
        }
    }
}

/** The stored value of [option] in this channel, or its default when the channel does not set it. */
fun Channel.option(option: ChannelOption): String = options[option.key] ?: option.default
