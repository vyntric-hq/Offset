/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vyntric.offset.AppContainer
import com.vyntric.offset.domain.RadioOperations
import com.vyntric.offset.ui.components.KeepScreenOn
import com.vyntric.offset.domain.WriteRadio
import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.ui.components.LocalBottomInset
import com.vyntric.offset.ui.components.OffsetSheet
import com.vyntric.offset.ui.components.PrimaryButton
import com.vyntric.offset.ui.components.SectionFooter
import com.vyntric.offset.ui.components.ThinProgress
import com.vyntric.offset.ui.components.rememberHaptics
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.images.ImageDetailScreen
import com.vyntric.offset.ui.images.ImagesScreen
import com.vyntric.offset.ui.radio.RadioScreen
import com.vyntric.offset.ui.repeaters.RepeatersScreen
import com.vyntric.offset.ui.settings.AboutScreen
import com.vyntric.offset.ui.settings.SettingsScreen
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    RADIO("radio", "Radio", Icons.Rounded.Radio),
    REPEATERS("repeaters", "Repeaters", Icons.Rounded.CellTower),
    IMAGES("images", "Images", Icons.Rounded.Inventory2),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

private val TabBarHeight = 54.dp

@Composable
fun OffsetRoot(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val current = Tab.entries.firstOrNull { route == it.route || route.startsWith(it.route + "/") }
        ?: if (route == "about") Tab.SETTINGS else Tab.RADIO
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val radioState by container.operations.state.collectAsStateWithLifecycle()
    KeepScreenOn(radioState is RadioOperations.State.Running)

    Box(Modifier.fillMaxSize().background(OffsetTheme.colors.background)) {
        CompositionLocalProvider(LocalBottomInset provides TabBarHeight + navInset) {
            NavHost(
                navController = nav,
                startDestination = Tab.RADIO.route,
                enterTransition = { fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 12 } },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(160)) },
                popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { it / 12 } },
            ) {
                composable(Tab.RADIO.route) { RadioScreen(container) }
                composable(Tab.REPEATERS.route) { RepeatersScreen(container) }
                composable(Tab.IMAGES.route) { ImagesScreen(container) { id -> nav.navigate("images/$id") } }
                composable("images/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments?.getLong("id") ?: 0L
                    ImageDetailScreen(container, id, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("images/$it") })
                }
                composable(Tab.SETTINGS.route) { SettingsScreen(container) { nav.navigate("about") } }
                composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            }
        }
        TabBar(current, Modifier.align(Alignment.BottomCenter)) { tab -> nav.selectTab(tab.route) }
        WriteOperationSheet(container) { id -> nav.navigate("images/$id") }
    }
}

private fun NavHostController.selectTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TabBar(current: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val colors = OffsetTheme.colors
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().background(colors.chrome)) {
        Spacer(Modifier.fillMaxWidth().height(Dimens.hairline).background(colors.separator))
        Row(Modifier.fillMaxWidth().height(TabBarHeight).selectableGroup()) {
            Tab.entries.forEach { tab ->
                val selected = tab == current
                val tint = if (selected) colors.accent else colors.secondaryLabel
                Column(
                    Modifier
                        .weight(1f)
                        .height(TabBarHeight)
                        .selectable(selected = selected, role = Role.Tab) {
                            if (!selected) haptics.select()
                            onSelect(tab)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                    // At large font sizes a label shrinks to fit its tab instead of being cut off.
                    Text(
                        tab.label,
                        style = OffsetTheme.type.caption,
                        color = tint,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = OffsetTheme.type.caption.fontSize),
                    )
                }
            }
        }
        Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** Shows write progress (cannot be dismissed while running) and then the outcome, from any tab. */
@Composable
private fun WriteOperationSheet(container: AppContainer, openImage: (Long) -> Unit) {
    val ops = container.operations
    val state by ops.state.collectAsStateWithLifecycle()
    val result by ops.result.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val running = (state as? RadioOperations.State.Running)?.takeIf { it.kind == RadioOperations.Kind.WRITE }
    val done = result as? RadioOperations.Result.WriteDone

    LaunchedEffect(done) {
        when (done?.outcome) {
            is WriteRadio.Outcome.Verified -> haptics.success()
            null -> Unit
            else -> haptics.failure()
        }
    }

    if (running == null && done == null) return
    OffsetSheet(onDismiss = { done?.let(ops::consume) }, title = if (running != null) "Writing" else "Write finished", dismissible = running == null) {
        if (running != null) {
            val p = running.progress
            val phase = when (p?.phase) {
                null, CloneProgress.Phase.IDENTIFYING -> "Contacting radio"
                CloneProgress.Phase.READING -> "Reading radio before writing"
                CloneProgress.Phase.WRITING -> "Writing channels"
                CloneProgress.Phase.VERIFYING -> "Reading back to verify"
            }
            Column(Modifier.padding(Dimens.gutter)) {
                Text(phase, style = OffsetTheme.type.headline, color = OffsetTheme.colors.label)
                ThinProgress(p?.takeIf { it.bytesTotal > 0 }?.fraction ?: 0f, Modifier.padding(vertical = 12.dp))
                Text(
                    if (p != null && p.bytesTotal > 0) Fmt.bytes(p.bytesDone, p.bytesTotal) else "Starting",
                    style = OffsetTheme.type.footnote, color = OffsetTheme.colors.secondaryLabel,
                )
                Text("Keep the cable connected and the radio on.", style = OffsetTheme.type.subhead,
                    color = OffsetTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 16.dp))
            }
        } else if (done != null) {
            Outcome(done.outcome, onOpen = { id -> ops.consume(done); openImage(id) }, onDone = { ops.consume(done) })
        }
    }
}

@Composable
private fun Outcome(outcome: WriteRadio.Outcome, onOpen: (Long) -> Unit, onDone: () -> Unit) {
    val colors = OffsetTheme.colors
    val (icon, tint, title, body) = when (outcome) {
        is WriteRadio.Outcome.Verified -> Quad(Icons.Rounded.CheckCircle, colors.success, "Written and verified",
            "${outcome.blocksWritten} memory blocks written and read back correctly. " +
                (outcome.saveError ?: "The result is saved in Images, and a backup of the radio from before the write is there too."))
        is WriteRadio.Outcome.Mismatch -> Quad(Icons.Rounded.Warning, colors.warning, "Verification found differences",
            "Channels ${outcome.channels.joinToString(", ")} did not read back as written. Check them on the radio. " +
                "The backup from before the write is in Images if you want to restore it." +
                (outcome.saveError?.let { " $it" } ?: ""))
        is WriteRadio.Outcome.Blocked -> Quad(Icons.Rounded.Error, colors.destructive, "Nothing was written",
            outcome.reasons.joinToString("\n"))
        is WriteRadio.Outcome.Failed -> Quad(Icons.Rounded.Error, colors.destructive,
            if (outcome.radioMayBePartial) "Write interrupted" else "Write not completed",
            (if (outcome.radioMayBePartial) "The radio may now hold a mix of old and new channels. Reconnect the cable, read the radio, " +
                "and restore the backup from Images. " else "") + outcome.message)
    }
    Column(Modifier.fillMaxWidth().padding(Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
        Text(title, style = OffsetTheme.type.title, color = colors.label, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
    SectionFooter(body)
    val backup = when (outcome) {
        is WriteRadio.Outcome.Failed -> outcome.backupImageId
        is WriteRadio.Outcome.Mismatch -> outcome.backupImageId
        else -> null
    }
    val saved = when (outcome) {
        is WriteRadio.Outcome.Verified -> outcome.savedImageId
        is WriteRadio.Outcome.Mismatch -> outcome.savedImageId
        else -> null
    }
    Column(Modifier.padding(Dimens.gutter)) {
        PrimaryButton("Done", onClick = onDone)
        if (backup != null) {
            Text("Open backup", style = OffsetTheme.type.body, color = colors.accent, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tappable { onOpen(backup) }.padding(12.dp))
        } else if (saved != null) {
            Text("Open saved image", style = OffsetTheme.type.body, color = colors.accent, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tappable { onOpen(saved) }.padding(12.dp))
        }
    }
}

private data class Quad(val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color, val title: String, val body: String)
