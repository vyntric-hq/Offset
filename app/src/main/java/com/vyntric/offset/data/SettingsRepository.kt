/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.protocol.Band
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units { MILES, KILOMETERS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class BandFilter(val bands: Set<Band>, val label: String) {
    TWO_METER(setOf(Band.TWO_METER), "2m"),
    SEVENTY_CM(setOf(Band.SEVENTY_CM), "70cm"),
    BOTH(setOf(Band.TWO_METER, Band.SEVENTY_CM), "Both"),
}

data class Settings(
    /** The repeater sources searched together; never empty. */
    val providers: Set<RepeaterSource> = setOf(RepeaterSource.HEARHAM),
    val units: Units = Units.MILES,
    /** One of [RADIUS_OPTIONS], in the current [units]; [CLOSEST] means the closest repeaters. */
    val defaultRadius: Int = 25,
    val defaultBand: BandFilter = BandFilter.BOTH,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val haptics: Boolean = true,
    /** Debug builds only: talk to the simulated radio instead of USB. */
    val simulatedRadio: Boolean = false,
    /** The user has read the GMRS licensing and certification warning. */
    val gmrsAcknowledged: Boolean = false,
    /** The radio type to read, a RadioDriver id. */
    val radioType: String = "uv5r",
) {
    companion object {
        /** Distance option meaning "the closest [CLOSEST_COUNT] repeaters, however far". */
        const val CLOSEST = 0
        const val CLOSEST_COUNT = 100
        val RADIUS_OPTIONS = listOf(CLOSEST, 10, 25, 50, 100)
    }
}

/** Plain (non secret) preferences, exposed as a flow. */
class SettingsRepository(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(K_PROVIDERS, next.providers.joinToString(",") { it.name })
            putString(K_UNITS, next.units.name)
            putInt(K_RADIUS, next.defaultRadius)
            putString(K_BAND, next.defaultBand.name)
            putString(K_THEME, next.theme.name)
            putBoolean(K_HAPTICS, next.haptics)
            putBoolean(K_SIM, next.simulatedRadio)
            putBoolean(K_GMRS_ACK, next.gmrsAcknowledged)
            putString(K_RADIO, next.radioType)
        }
        _settings.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            providers = readProviders() ?: d.providers,
            units = enumOr(prefs.getString(K_UNITS, null), d.units),
            defaultRadius = prefs.getInt(K_RADIUS, d.defaultRadius).takeIf { it in Settings.RADIUS_OPTIONS } ?: d.defaultRadius,
            defaultBand = enumOr(prefs.getString(K_BAND, null), d.defaultBand),
            theme = enumOr(prefs.getString(K_THEME, null), d.theme),
            haptics = prefs.getBoolean(K_HAPTICS, d.haptics),
            simulatedRadio = prefs.getBoolean(K_SIM, d.simulatedRadio),
            gmrsAcknowledged = prefs.getBoolean(K_GMRS_ACK, d.gmrsAcknowledged),
            radioType = prefs.getString(K_RADIO, null) ?: d.radioType,
        )
    }

    /** The saved set, or the single source 0.6.4 and earlier saved, or null for neither. */
    private fun readProviders(): Set<RepeaterSource>? {
        val names = prefs.getString(K_PROVIDERS, null)?.split(',') ?: listOfNotNull(prefs.getString(K_PROVIDER, null))
        return names.mapNotNull { n -> RepeaterSource.providers.firstOrNull { it.name == n } }.toSet().ifEmpty { null }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        /** Single source, written by 0.6.4 and earlier; read once for migration. */
        const val K_PROVIDER = "provider"
        const val K_PROVIDERS = "providers"
        const val K_UNITS = "units"
        const val K_RADIUS = "default_radius"
        const val K_BAND = "default_band"
        const val K_THEME = "theme"
        const val K_HAPTICS = "haptics"
        const val K_SIM = "simulated_radio"
        const val K_GMRS_ACK = "gmrs_acknowledged"
        const val K_RADIO = "radio_type"
    }
}
