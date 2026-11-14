/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyntric.offset.AppContainer
import com.vyntric.offset.data.Settings
import com.vyntric.offset.domain.RepeaterException
import com.vyntric.offset.domain.RepeaterSource
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
    val message: String? = null,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(c.settings.settings, c.credentials.credentials, local) { s, creds, l ->
        l.copy(settings = s, token = creds.token, email = creds.email, secureStorage = c.credentials.available)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init { refreshCaches() }

    fun update(transform: (Settings) -> Settings) = c.settings.update(transform)

    fun saveCredentials(token: String, email: String) {
        c.credentials.save(token, email)
        local.update { it.copy(testResult = null) }
    }

    fun clearCredentials() = c.credentials.clear()

    fun testConnection() = viewModelScope.launch {
        local.update { it.copy(testing = true, testResult = null) }
        val (ok, text) = try {
            val n = c.repeaterBook.testConnection()
            true to "Connected. RepeaterBook returned $n analog repeaters for the test query."
        } catch (e: RepeaterException) {
            false to (e.message ?: "Connection failed.")
        }
        local.update { it.copy(testing = false, testResult = text, testOk = ok) }
    }

    fun clearCache(source: RepeaterSource) = viewModelScope.launch {
        c.provider(source).clearCache()
        local.update { it.copy(message = "${source.displayName} cache cleared.") }
        refreshCaches()
    }

    fun resetSimulatedRadio() = viewModelScope.launch {
        c.simulatedLink.reset()
        c.radioSession.clear()
        local.update { it.copy(message = "Simulated radio reset to its factory image.") }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun refreshCaches() = viewModelScope.launch {
        val dates = RepeaterSource.providers.associateWith { c.provider(it).cachedAtMillis() }
        local.update { it.copy(cacheDates = dates) }
    }
}
