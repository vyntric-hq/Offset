/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.settings

import com.vyntric.offset.ui.components.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.Settings
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.data.TerrainRepository
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.UsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: Settings = Settings(),
    val token: String = "",
    val email: String = "",
    val secureStorage: Boolean = true,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean = false,
    val cacheDates: Map<RepeaterSource, Long?> = emptyMap(),
    /** Elevation tiles Reach keeps on the phone, in bytes. */
    val terrainBytes: Long = 0,
    /** A state's terrain being saved for offline use: which, and how far along. */
    val terrainDownload: Pair<UsState, Float>? = null,
    val message: Toast? = null,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(c.settings.settings, c.credentials.credentials, local) { s, creds, l ->
        l.copy(settings = s, token = creds.token, email = creds.email, secureStorage = c.credentials.available)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init { refreshCaches() }

    fun update(transform: (Settings) -> Settings) = c.settings.update(transform)

    /** Saves, then tests at once so the user sees whether the token works. */
    fun saveCredentials(token: String, email: String) {
        c.credentials.save(token, email)
        local.update { it.copy(testResult = null) }
        if (token.isNotBlank() && email.contains('@')) testConnection()
    }

    /**
     * Removes the token and stops searching RepeaterBook, which cannot answer without it; Hearham
     * takes over when RepeaterBook was the only source.
     */
    fun clearCredentials() {
        c.credentials.clear()
        local.update { it.copy(testResult = null) }
        c.settings.update { s ->
            s.copy(providers = (s.providers - RepeaterSource.REPEATERBOOK).ifEmpty { setOf(RepeaterSource.HEARHAM) })
        }
    }

    /** Turns [source] on or off; the last source stays on. Turning on RepeaterBook needs a token. */
    fun toggleSource(source: RepeaterSource) = c.settings.update { s ->
        val next = if (source in s.providers) s.providers - source else s.providers + source
        if (next.isEmpty()) s else s.copy(providers = next)
    }

    fun testConnection() {
        if (local.value.testing) return
        viewModelScope.launch {
            local.update { it.copy(testing = true, testResult = null) }
            val tested = c.credentials.credentials.value
            val (ok, text) = try {
                c.repeaterBook.testConnection()
                true to "Connected"
            } catch (e: RepeaterException) {
                false to (e.message ?: "Connection failed.")
            }
            // The token or email changed while this ran: the outcome is for credentials no longer saved.
            if (c.credentials.credentials.value != tested) {
                local.update { it.copy(testing = false) }
                return@launch
            }
            local.update { it.copy(testing = false, testResult = text, testOk = ok) }
            if (ok) c.settings.update { it.copy(providers = it.providers + RepeaterSource.REPEATERBOOK) }
        }
    }

    fun clearCache(source: RepeaterSource) = viewModelScope.launch {
        c.provider(source).clearCache()
        local.update { it.copy(message = Toast("${source.displayName} cache cleared.")) }
        refreshCaches()
    }

    fun resetSimulatedRadio() = viewModelScope.launch {
        c.simulatedLink.reset()
        c.radioSession.clear()
        local.update { it.copy(message = Toast("Simulated radio reset to its factory image.")) }
    }

    /** Rough size of saving [state]'s terrain, for the picker. */
    fun terrainEstimateBytes(state: UsState): Long =
        c.terrain.tilesInBox(state.maxLat, state.minLon, state.minLat, state.maxLon).size * TerrainRepository.AVERAGE_TILE_BYTES

    /** Saves the terrain for [state] so Reach works there without signal. */
    fun downloadTerrain(state: UsState) {
        if (local.value.terrainDownload != null) return
        viewModelScope.launch {
            local.update { it.copy(terrainDownload = state to 0f) }
            val message = try {
                val failed = c.terrain.download(state.maxLat, state.minLon, state.minLat, state.maxLon) { done, total ->
                    local.update { it.copy(terrainDownload = state to done.toFloat() / total) }
                }
                if (failed == 0) Toast("Terrain for ${state.displayName} saved for offline use.")
                else Toast("Saved most of ${state.displayName}; $failed tiles could not be fetched.", isError = true)
            } catch (e: java.io.IOException) {
                Toast("Could not save terrain for ${state.displayName}: ${e.message ?: "storage error"}.", isError = true)
            } finally {
                local.update { it.copy(terrainDownload = null) }
            }
            local.update { it.copy(message = message) }
            refreshCaches()
        }
    }

    fun clearTerrain() = viewModelScope.launch {
        c.terrain.clear()
        local.update { it.copy(message = Toast("Saved terrain removed.")) }
        refreshCaches()
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun refreshCaches() = viewModelScope.launch {
        val dates = RepeaterSource.providers.associateWith { c.provider(it).cachedAtMillis() }
        val terrain = c.terrain.sizeBytes()
        local.update { it.copy(cacheDates = dates, terrainBytes = terrain) }
    }
}
