/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.map

import com.vyntric.offset.ui.components.CreditLine
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vyntric.offset.BuildConfig
import com.vyntric.offset.domain.Repeater
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.ui.Fmt
import com.vyntric.offset.ui.components.LocalBottomInset
import com.vyntric.offset.ui.components.tappable
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

/** Categorical pin colours, the same in the legend and on the map. */
object MapColors {
    val TwoMeter = Color(0xFF0A84FF)
    val SeventyCm = Color(0xFF12A594)
    val Gmrs = Color(0xFFFF9500)
    val Other = Color(0xFF8E8E93)
}

@Composable
fun RepeaterMapScreen(
    title: String,
    repeaters: List<Repeater>,
    me: Pair<Double, Double>?,
    nationwide: Boolean,
    meLabel: String = "You",
    loading: Boolean,
    sources: Set<RepeaterSource>,
    onBack: () -> Unit,
    onRepeater: (Repeater) -> Unit,
) {
    val context = LocalContext.current
    val colors = OffsetTheme.colors
    var dark by rememberSaveable { mutableStateOf(colors.isDark) }
    val controller = remember { RepeaterMapController(context, "Offset/${BuildConfig.VERSION_NAME} (${BuildConfig.GITHUB_REPO})") }
    val current by rememberUpdatedState(repeaters)
    val tapped by rememberUpdatedState(onRepeater)

    BackHandler(onBack = onBack)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller.onRepeaterTap = { id -> current.firstOrNull { it.id == id }?.let(tapped) }
        onDispose {
            lifecycle.removeObserver(observer)
            controller.onDestroy()
        }
    }
    LaunchedEffect(dark) {
        controller.setStyle(
            if (dark) RepeaterMapController.STYLE_DARK else RepeaterMapController.STYLE_LIGHT,
            RepeaterMapController.Palette(
                MapColors.TwoMeter.toArgb(), MapColors.SeventyCm.toArgb(), MapColors.Gmrs.toArgb(), MapColors.Other.toArgb(),
                colors.destructive.toArgb(), dark,
            ),
        )
    }
    LaunchedEffect(repeaters, me, nationwide, meLabel) { controller.setData(repeaters, me, nationwide, meLabel) }

    val bottom = LocalBottomInset.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
        AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize().padding(bottom = bottom))

        // Top bar
        Column(Modifier.fillMaxWidth().background(colors.chrome).windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Row(
                    Modifier.align(Alignment.CenterStart).tappable("Back to list", onBack).padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Text("Repeaters", style = OffsetTheme.type.body, color = colors.accent)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = OffsetTheme.type.headline, color = colors.label)
                    Text("${repeaters.count { it.latitude != null }} on map", style = OffsetTheme.type.caption, color = colors.secondaryLabel)
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(44.dp)
                        .tappable(if (dark) "Light map" else "Dark map") { dark = !dark },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, contentDescription = null, tint = colors.accent)
                }
            }
            Spacer(Modifier.fillMaxWidth().height(Dimens.hairline).background(colors.separator))
        }

        if (loading) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(20.dp)) {
                CircularProgressIndicator(color = colors.secondaryLabel, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
        }

        // Legend with the data source credit, bottom left.
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottom + 12.dp).widthIn(max = 220.dp)
                .shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val kinds = repeaters.map { it.kind }.toSet()
            listOf("2m" to MapColors.TwoMeter, "70cm" to MapColors.SeventyCm, "GMRS" to MapColors.Gmrs)
                .filter { it.first in kinds || kinds.isEmpty() }
                .forEach { (label, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                        Text("  $label", style = OffsetTheme.type.footnote, color = colors.label)
                    }
                }
            CreditLine(Fmt.credits(sources, repeaters), OffsetTheme.type.caption)
        }

        // Recenter, bottom right above MapLibre's attribution button.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom + 56.dp).size(52.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(colors.surface)
                .tappable("Fit repeaters on screen") { controller.recenter() }
                .semantics { contentDescription = "Recenter map" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null, tint = colors.accent)
        }
    }
}
