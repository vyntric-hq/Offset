/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.settings

import androidx.compose.foundation.layout.width
import com.vyntric.offset.ui.LocalTimeDisplay
import com.vyntric.offset.ui.components.SourceBadge
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
import com.vyntric.offset.data.ClockFormat
import com.vyntric.offset.data.Settings
import com.vyntric.offset.data.ThemeMode
import com.vyntric.offset.data.Units
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.domain.UsState
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.ActionOutcome
import com.vyntric.offset.ui.components.ActionRow
import com.vyntric.offset.ui.components.ButtonRow
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.CellRow
import com.vyntric.offset.ui.components.InsetSection
import com.vyntric.offset.ui.components.LargeTitleScreen
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
    var pickingZone by remember { mutableStateOf(false) }
    if (pickingZone) {
        TimeZoneSheet(s.settings.timeZone, onDismiss = { pickingZone = false }) { zone ->
            vm.update { it.copy(timeZone = zone) }
            pickingZone = false
        }
    }

    LaunchedEffect(s.message) {
        if (s.message != null) { kotlinx.coroutines.delay(3_000); vm.dismissMessage() }
    }

    LargeTitleScreen(title = "Settings", toast = s.message) {
        item(key = "source") {
            InsetSection(header = "Repeater sources") {
                RepeaterSource.providers.forEach { src ->
                    val on = src in s.settings.providers
                    val needsToken = src == RepeaterSource.REPEATERBOOK && s.token.isBlank()
                    cell {
                        CellRow(
                            src.displayName,
                            subtitle = when (src) {
                                RepeaterSource.HEARHAM -> "Free, works offline"
                                RepeaterSource.REPEATERBOOK -> if (needsToken) "Add a token below" else null
                                RepeaterSource.CSV -> "Your imported list"
                                else -> null
                            },
                            leading = { Box(Modifier.width(34.dp)) { SourceBadge(src) } },
                            trailing = {
                                if (on) Icon(Icons.Rounded.Check, contentDescription = "On", tint = OffsetTheme.colors.accent)
                            },
                            enabled = on || !needsToken,
                            onClickLabel = if (on) "Stop using ${src.displayName}" else "Use ${src.displayName}",
                        ) {
                            haptics.select()
                            vm.toggleSource(src)
                        }
                    }
                }
            }
        }
        item(key = "rb") {
            InsetSection(
                header = "RepeaterBook",
                footer = if (!s.secureStorage) "This phone cannot store a token securely." else null,
            ) {
                cell {
                    TextFieldRow("Token", token, { token = it.trim() }, placeholder = "rbuapp_...",
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                cell {
                    TextFieldRow("Email", email, { email = it.trim() }, placeholder = "you@example.org",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
                if (dirty) cell { ButtonRow("Save and test", enabled = s.secureStorage) { vm.saveCredentials(token, email) } }
                if (!dirty && s.token.isNotBlank()) cell { TestRow(s) { vm.testConnection() } }
                if (s.token.isNotBlank()) cell { ButtonRow("Remove token", destructive = true) { vm.clearCredentials() } }
                cell { ButtonRow("Get a token") { uri.openUri(RB_TOKENS) } }
                cell { ButtonRow("Create a RepeaterBook account") { uri.openUri(RB_REGISTER) } }
            }
        }
        item(key = "cache") {
            InsetSection(header = "Cached data") {
                RepeaterSource.providers.forEach { src ->
                    val at = s.cacheDates[src]
                    cell {
                        CellRow(
                            if (src == RepeaterSource.CSV) "Clear imported CSV" else "Clear ${src.displayName} cache",
                            subtitle = at?.let { "From ${LocalTimeDisplay.current.dateTime(it)}" } ?: "Nothing cached",
                            titleColor = OffsetTheme.colors.accent,
                            enabled = at != null,
                        ) { vm.clearCache(src) }
                    }
                }
            }
        }
        item(key = "terrain") {
            var picking by remember { mutableStateOf(false) }
            val dl = s.terrainDownload
            InsetSection(
                header = "Reach terrain",
            ) {
                cell { CellRow("Terrain on this phone", value = megabytes(s.terrainBytes)) }
                cell {
                    if (dl != null) {
                        CellRow("Saving ${dl.first.displayName}", value = "${(dl.second * 100).toInt()}%")
                    } else {
                        ButtonRow("Save a state for offline") { picking = true }
                    }
                }
                cell { ButtonRow("Remove saved terrain", destructive = true, enabled = s.terrainBytes > 0 && dl == null) { vm.clearTerrain() } }
            }
            if (picking) {
                OffsetSheet(onDismiss = { picking = false }, title = "Save terrain", leadingAction = { BarAction("Cancel") { picking = false } }) {
                    InsetSection {
                        UsState.entries.sortedBy { it.displayName }.forEach { st ->
                            cell { CellRow(st.displayName, value = "about ${megabytes(vm.terrainEstimateBytes(st))}", onClick = { picking = false; vm.downloadTerrain(st) }) }
                        }
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
        item(key = "time") {
            InsetSection(header = "Date and time") {
                cell {
                    SegmentedRow("Clock", listOf("Phone", "12-hour", "24-hour"), s.settings.clockFormat.ordinal) { i ->
                        vm.update { it.copy(clockFormat = ClockFormat.entries[i]) }
                    }
                }
                cell { CellRow("Time zone", value = s.settings.timeZone ?: "Phone", chevron = true, onClick = { pickingZone = true }) }
            }
        }
        if (BuildConfig.DEBUG) {
            item(key = "debug") {
                InsetSection(header = "Debug") {
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

/** Phone, UTC, or any zone found by searching its name ("Chicago", "Europe/"). */
@Composable
private fun TimeZoneSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { java.time.ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val matches = remember(query) {
        val q = query.trim().replace(' ', '_')
        if (q.isEmpty()) emptyList() else all.filter { it.contains(q, ignoreCase = true) }.take(60)
    }
    OffsetSheet(onDismiss = onDismiss, title = "Time zone", leadingAction = { BarAction("Cancel", onClick = onDismiss) }) {
        InsetSection {
            cell { TextFieldRow("Search", query, { query = it }, placeholder = "City or region", labelWidth = 80.dp) }
        }
        InsetSection {
            cell { ZoneRow("Phone", java.time.ZoneId.systemDefault().id, current == null) { onPick(null) } }
            cell { ZoneRow("UTC", null, current == "UTC") { onPick("UTC") } }
            matches.forEach { id -> cell { ZoneRow(id.replace('_', ' '), null, current == id) { onPick(id) } } }
        }
    }
}

@Composable
private fun ZoneRow(title: String, value: String?, selected: Boolean, onClick: () -> Unit) = CellRow(
    title,
    value = value,
    trailing = if (selected) { { Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OffsetTheme.colors.accent) } } else null,
    onClick = onClick,
)

@Composable
private fun TestRow(s: SettingsUiState, onTest: () -> Unit) = ActionRow(
    "Test connection",
    pending = s.testing,
    pendingTitle = "Testing...",
    outcome = s.testResult?.let { ActionOutcome(it, ok = s.testOk) },
    enabled = s.email.contains('@'),
    onClick = onTest,
)

private fun megabytes(bytes: Long): String =
    if (bytes < 1_000_000) "${bytes / 1000} KB" else String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)

private const val RB_REGISTER = "https://www.repeaterbook.com/index.php/component/users/?view=registration"
private const val RB_TOKENS = "https://www.repeaterbook.com/user/api_apps.php"
