/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Drag to reorder inside a LazyColumn. Items are identified by key; [onMove] receives the key
 * being dragged and the key it now sits on. Only keys accepted by [canReorder] take part.
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val canReorder: (Any) -> Boolean,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    internal fun start(key: Any) {
        dragging = key
        offset = 0f
    }

    internal fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = visible.firstOrNull {
            it.key != key && canReorder(it.key) && center >= it.offset && center <= it.offset + it.size
        } ?: return
        onMove(key, target.key)
        offset += current.offset - target.offset
    }

    internal fun end() {
        dragging = null
        offset = 0f
    }
}

@Composable
fun rememberReorderState(listState: LazyListState, canReorder: (Any) -> Boolean, onMove: (Any, Any) -> Unit): ReorderState {
    val move by rememberUpdatedState(onMove)
    val can by rememberUpdatedState(canReorder)
    return remember(listState) { ReorderState(listState, { a, b -> move(a, b) }, { can(it) }) }
}

/** Applied to the row being reordered: follows the finger and floats above its neighbours. */
fun Modifier.reorderable(state: ReorderState, key: Any): Modifier = this
    .zIndex(if (state.dragging == key) 1f else 0f)
    .graphicsLayer {
        if (state.dragging == key) {
            translationY = state.offset
            shadowElevation = 12f
            scaleX = 1.02f
            scaleY = 1.02f
        }
    }

/** Applied to the drag handle inside the row. */
fun Modifier.dragHandle(state: ReorderState, key: Any, onStart: () -> Unit = {}): Modifier = this.pointerInput(key) {
    detectDragGestures(
        onDragStart = { onStart(); state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    ) { change, amount ->
        change.consume()
        state.drag(amount.y)
    }
}
