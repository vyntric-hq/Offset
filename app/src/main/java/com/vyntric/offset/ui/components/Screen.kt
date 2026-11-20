/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyntric.offset.ui.theme.Dimens
import com.vyntric.offset.ui.theme.OffsetTheme

/** Height reserved at the bottom of every screen for the translucent tab bar. */
val LocalBottomInset = compositionLocalOf { 0.dp }

private val TopBarHeight = 44.dp

/**
 * A screen with a large bold title that collapses into a centered small title as content
 * scrolls under a translucent top bar.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    bottomBar: (@Composable () -> Unit)? = null,
    bottomBarHeight: Dp = 0.dp,
    toast: Toast? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = OffsetTheme.colors
    val type = OffsetTheme.type
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }
    val progress by animateFloatAsState(if (collapsed) 1f else 0f, tween(180), label = "titleCollapse")
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = LocalBottomInset.current
    val density = LocalDensity.current
    // The bar grows with the font size; the list starts below whatever height it measured.
    var barHeight by remember { mutableStateOf(TopBarHeight) }

    Box(modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop + barHeight, bottom = bottomInset + bottomBarHeight + 24.dp),
        ) {
            item(key = "large-title") {
                Text(
                    title,
                    style = type.largeTitle,
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = Dimens.gutter, end = Dimens.gutter, top = 2.dp, bottom = 12.dp)
                        .graphicsLayer { alpha = 1f - progress }
                        .semantics { heading() },
                )
            }
            content()
        }

        // Top bar: transparent at rest, translucent chrome with hairline once collapsed.
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.chrome.copy(alpha = colors.chrome.alpha * progress))
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            TopBarLayout(
                modifier = Modifier.fillMaxWidth().onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                leading = {
                    if (onBack != null) {
                        Row(
                            Modifier
                                .clickable(role = Role.Button, onClickLabel = backLabel, onClick = onBack)
                                .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                            Text(backLabel, style = type.body, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                title = {
                    Text(
                        title,
                        style = type.headline,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp).alpha(progress),
                    )
                },
                actions = {
                    Row(
                        Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        content = actions,
                    )
                },
            )
            Spacer(Modifier.fillMaxWidth().height(Dimens.hairline).background(colors.separator.copy(alpha = colors.separator.alpha * progress)))
        }

        if (bottomBar != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) {
                CompositionLocalProvider(LocalBottomInset provides 0.dp) { bottomBar() }
            }
        }

        ToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + bottomBarHeight + 12.dp))
    }
}

/**
 * Lays out the top bar: [leading] at the start, [actions] at the end, and [title] centered on the
 * bar when it fits between them, otherwise in the space left between them (ellipsized). The bar is
 * at least [TopBarHeight] tall and grows when a large font makes any part taller, so the title
 * never draws over the actions.
 */
@Composable
private fun TopBarLayout(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(leading, title, actions), modifier = modifier) { (lead, ttl, act), constraints ->
        val width = constraints.maxWidth
        // Controls before the title: the back button keeps up to half the bar, the actions get
        // everything else they need, and the title takes what is left (it repeats the large title).
        val leadCap = minOf(lead.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) } ?: 0, width / 2)
        val actP = act.map { it.measure(Constraints(maxWidth = (width - leadCap).coerceAtLeast(0))) }
        val actW = actP.maxOfOrNull { it.width } ?: 0
        val leadP = lead.map { it.measure(Constraints(maxWidth = (width - actW).coerceAtLeast(0))) }
        val leadW = leadP.maxOfOrNull { it.width } ?: 0
        val side = maxOf(leadW, actW)
        val centered = width - 2 * side >= MinCenteredTitle.roundToPx()
        val titleMax = (if (centered) width - 2 * side else width - leadW - actW).coerceAtLeast(0)
        val titleP = ttl.map { it.measure(Constraints(maxWidth = titleMax)) }
        val titleW = titleP.maxOfOrNull { it.width } ?: 0
        val height = maxOf(
            TopBarHeight.roundToPx(),
            leadP.maxOfOrNull { it.height } ?: 0,
            actP.maxOfOrNull { it.height } ?: 0,
            titleP.maxOfOrNull { it.height } ?: 0,
        )
        layout(width, height) {
            leadP.forEach { it.place(0, (height - it.height) / 2) }
            val titleX = if (centered) (width - titleW) / 2 else leadW
            titleP.forEach { it.place(titleX, (height - it.height) / 2) }
            actP.forEach { it.place(width - it.width, (height - it.height) / 2) }
        }
    }
}

/** Below this width a centered title would be unreadable, so it moves into the free space. */
private val MinCenteredTitle = 80.dp

/** A text button for the top bar, in the accent colour. */
@Composable
fun BarAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Text(
        text,
        style = OffsetTheme.type.body,
        color = if (enabled) colors.accent else colors.tertiaryLabel,
        maxLines = 1,
        // On a narrow screen at a large font size the label shrinks rather than wrapping or clipping.
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = OffsetTheme.type.body.fontSize),
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/**
 * A short confirmation or failure shown over the bottom of a screen, wherever it is scrolled.
 * Each one is distinct, so the same text shown twice restarts its display time.
 */
data class Toast(val text: String, val isError: Boolean = false, val id: Long = nextToastId.getAndIncrement())

private val nextToastId = java.util.concurrent.atomic.AtomicLong()

@Composable
private fun ToastHost(toast: Toast?, modifier: Modifier) {
    val colors = OffsetTheme.colors
    // Keeps the last toast on screen while it fades out.
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    AnimatedVisibility(toast != null, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        val t = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(horizontal = Dimens.gutter)
                .shadow(8.dp, RoundedCornerShape(Dimens.groupCorner))
                .background(colors.surfaceRaised, RoundedCornerShape(Dimens.groupCorner))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (t.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (t.isError) colors.destructive else colors.success,
                modifier = Modifier.size(22.dp),
            )
            Text(t.text, style = OffsetTheme.type.subhead, color = colors.label)
        }
    }
}
