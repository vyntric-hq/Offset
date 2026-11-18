/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen from turning off while [enabled], as during a radio read or write, so the
 * phone does not sleep with the progress hidden. Needs no permission (it is the window flag
 * behind View.keepScreenOn) and ends when the screen leaves composition.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
