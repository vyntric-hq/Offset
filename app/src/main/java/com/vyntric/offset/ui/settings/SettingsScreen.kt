/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyntric.offset.AppContainer
import com.vyntric.offset.BuildConfig
import com.vyntric.offset.data.BandFilter
import com.vyntric.offset.data.Settings
import com.vyntric.offset.data.ThemeMode
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.LargeTitleScreen
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.SegmentedRow
import com.vyntric.offset.ui.components.TextFieldRow
import com.vyntric.offset.ui.components.ToggleRow
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.theme.OffsetTheme

@Composable
fun SettingsScreen(container: AppContainer, onAbout: () -> Unit) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val uri = LocalUriHandler.current
    var token by remember(s.token) { mutableStateOf(s.token) }
    var email by remember(s.email) { mutableStateOf(s.email) }
    val dirty = token != s.token || email != s.email

    LaunchedEffect(s.message) {
        if (s.message != null) { kotlinx.coroutines.delay(3_000); vm.dismissMessage() }
    }

    LargeTitleScreen(title = "Settings") {
        s.message?.let { m -> item(key = "msg") { SectionFooter(m) } }
        item(key = "source") {
            InsetSection(header = "Repeater data source", footer = "Hearham is free and needs no account. RepeaterBook needs your own token. CSV searches a list you import.") {
                RepeaterSource.entries.forEach { src ->
                    cell {
                        CellRow(
                            src.displayName,
                            subtitle = when (src) {
                                RepeaterSource.HEARHAM -> "Default. Included with the app, works offline, refreshes weekly when online."
                                RepeaterSource.REPEATERBOOK -> if (s.token.isBlank()) "Off until a token is configured below." else "Your token is configured."
                                RepeaterSource.CSV -> "A CHIRP CSV from the file picker."
                            },
                            trailing = {
                                if (s.settings.provider == src) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OffsetTheme.colors.accent)
                            },
                            onClickLabel = "Use ${src.displayName}",
                        ) {
                            haptics.select()
                            vm.update { it.copy(provider = src) }
                        }
                    }
                }
            }
        }
        item(key = "rb") {
            InsetSection(
                header = "RepeaterBook",
                footer = if (!s.secureStorage) "Encrypted storage is unavailable on this device, so a token cannot be saved."
                else "Generate a personal token for Offset at repeaterbook.com under API Apps. It is stored encrypted on this phone " +
                    "and only ever sent to repeaterbook.com. Your email goes in the User-Agent, as RepeaterBook requires.",
            ) {
                cell {
                    TextFieldRow("Token", token, { token = it.trim() }, placeholder = "rbuapp_...",
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                cell {
                    TextFieldRow("Email", email, { email = it.trim() }, placeholder = "you@example.org",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
                if (dirty) cell { ButtonRow("Save", enabled = s.secureStorage) { vm.saveCredentials(token, email) } }
                cell { ButtonRow(if (s.testing) "Testing" else "Test connection", enabled = !dirty && !s.testing && s.token.isNotBlank() && s.email.contains('@')) { vm.testConnection() } }
                if (s.token.isNotBlank()) cell { ButtonRow("Remove token", destructive = true) { vm.clearCredentials() } }
                cell { ButtonRow("Get a token at repeaterbook.com") { uri.openUri("https://www.repeaterbook.com/user/api_apps.php") } }
            }
            s.testResult?.let { SectionFooter(it, Modifier.padding(top = 4.dp)) }
        }
        item(key = "cache") {
            InsetSection(header = "Cached data", footer = "Cached repeater data stays on this phone and is never shared or backed up.") {
                RepeaterSource.entries.forEach { src ->
                    val at = s.cacheDates[src]
                    cell {
                        CellRow(
                            if (src == RepeaterSource.CSV) "Clear imported CSV" else "Clear ${src.displayName} cache",
                            subtitle = at?.let { "From ${Fmt.dateTime(it)}" } ?: "Nothing cached",
                            titleColor = OffsetTheme.colors.accent,
                            enabled = at != null,
                        ) { vm.clearCache(src) }
                    }
                }
            }
        }
        item(key = "search") {
            InsetSection(header = "Search defaults") {
                cell { SegmentedRow("Units", listOf("Miles", "Kilometers"), s.settings.units.ordinal) { i -> vm.update { it.copy(units = Units.entries[i]) } } }
                cell {
                    val unit = if (s.settings.units == Units.MILES) "mi" else "km"
                    SegmentedRow("Default distance", Settings.RADIUS_OPTIONS.map { if (it == Settings.CLOSEST) "Closest" else "$it $unit" }, Settings.RADIUS_OPTIONS.indexOf(s.settings.defaultRadius)) { i ->
                        vm.update { it.copy(defaultRadius = Settings.RADIUS_OPTIONS[i]) }
                    }
                }
                cell { SegmentedRow("Default band", BandFilter.entries.map { it.label }, s.settings.defaultBand.ordinal) { i -> vm.update { it.copy(defaultBand = BandFilter.entries[i]) } } }
            }
        }
        item(key = "appearance") {
            InsetSection(header = "Appearance") {
                cell { SegmentedRow("Theme", listOf("System", "Light", "Dark"), s.settings.theme.ordinal) { i -> vm.update { it.copy(theme = ThemeMode.entries[i]) } } }
                cell { ToggleRow("Haptics", s.settings.haptics, { on -> vm.update { it.copy(haptics = on) } }) }
            }
        }
        if (BuildConfig.DEBUG) {
            item(key = "debug") {
                InsetSection(header = "Debug", footer = "Debug builds only. The simulated radio speaks the UV-5R clone protocol in memory, so every screen works without hardware.") {
                    cell { ToggleRow("Simulated radio", s.settings.simulatedRadio, { on -> vm.update { it.copy(simulatedRadio = on) } }) }
                    cell { ButtonRow("Reset simulated radio") { vm.resetSimulatedRadio() } }
                }
            }
        }
        item(key = "about") {
            InsetSection {
                cell { CellRow("About Offset", value = BuildConfig.VERSION_NAME, chevron = true, onClick = onAbout) }
            }
        }
        item(key = "pad") { Box(Modifier.padding(8.dp)) }
    }
}
