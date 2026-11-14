/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.domain.County
import java.io.InputStream

/**
 * US counties from the bundled Census Gazetteer (assets/places/counties.tsv, public domain),
 * written by scripts/update_counties.py. Loaded once, on first use.
 */
class CountyDirectory(private val open: () -> InputStream) {
    private val all: List<County> by lazy {
        runCatching { open().bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())
    }

    fun inState(abbr: String): List<County> = all.filter { it.state == abbr }

    val count: Int get() = all.size

    companion object {
        fun parse(text: String): List<County> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val c = line.split('\t')
                if (c.size < 4) return@mapNotNull null
                County(c[0], c[1], c[2].toDoubleOrNull() ?: return@mapNotNull null, c[3].toDoubleOrNull() ?: return@mapNotNull null)
            }.toList()
    }
}
