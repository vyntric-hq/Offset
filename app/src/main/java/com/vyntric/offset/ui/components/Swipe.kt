/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.launch

/**
 * Swipe left to reveal a destructive action; a long swipe performs it. The action is also
 * exposed as an accessibility custom action so it works without gestures.
 */
@Composable
fun SwipeActionRow(actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = OffsetTheme.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val actionWidth = with(density) { 88.dp.toPx() }
    val widthHolder = remember { floatArrayOf(1f) }

    Box(
        modifier
            .onSizeChanged { widthHolder[0] = it.width.toFloat() }
            .semantics { customActions = listOf(CustomAccessibilityAction(actionLabel) { onAction(); true }) },
    ) {
        // Drawn only while swiping, so it never bleeds through the cell's rounded corners at rest.
        if (offset.value < -0.5f) Box(
            Modifier.matchParentSize().background(colors.destructive),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier.width(88.dp).fillMaxHeight().clickable {
                    scope.launch { offset.snapTo(0f) }
                    onAction()
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = OffsetTheme.type.subhead, color = colors.onAccent)
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .background(colors.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val v = offset.value
                                when {
                                    v < -widthHolder[0] * 0.55f -> {
                                        haptics.select()
                                        offset.animateTo(-widthHolder[0], spring(stiffness = 900f))
                                        onAction()
                                        offset.snapTo(0f)
                                    }
                                    v < -actionWidth / 2 -> offset.animateTo(-actionWidth, spring(dampingRatio = 0.8f))
                                    else -> offset.animateTo(0f, spring(dampingRatio = 0.8f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(-widthHolder[0], 0f)) }
                    }
                },
        ) { content() }
    }
}
