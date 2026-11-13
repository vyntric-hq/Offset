/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

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
import androidx.compose.ui.unit.dp
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

    Box(modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop + TopBarHeight, bottom = bottomInset + bottomBarHeight + 24.dp),
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
            Box(Modifier.fillMaxWidth().height(TopBarHeight)) {
                Text(
                    title,
                    style = type.headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).alpha(progress),
                )
                if (onBack != null) {
                    Row(
                        Modifier
                            .align(Alignment.CenterStart)
                            .clickable(role = Role.Button, onClickLabel = backLabel, onClick = onBack)
                            .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Text(backLabel, style = type.body, color = colors.accent)
                    }
                }
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions,
                )
            }
            Spacer(Modifier.fillMaxWidth().height(Dimens.hairline).background(colors.separator.copy(alpha = colors.separator.alpha * progress)))
        }

        if (bottomBar != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) {
                CompositionLocalProvider(LocalBottomInset provides 0.dp) { bottomBar() }
            }
        }
    }
}

/** A text button for the top bar, in the accent colour. */
@Composable
fun BarAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = OffsetTheme.colors
    Text(
        text,
        style = OffsetTheme.type.body,
        color = if (enabled) colors.accent else colors.tertiaryLabel,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}
