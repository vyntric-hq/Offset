/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vyntric.offset.ui.components.KeepScreenOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screen stays on while a radio is being read or written, and only then. */
@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {
    @get:Rule val rule = createComposeRule()

    @Test fun keepsTheScreenOnOnlyWhileEnabled() {
        var enabled by mutableStateOf(false)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            KeepScreenOn(enabled)
        }
        rule.waitForIdle()
        assertFalse(view.keepScreenOn)
        enabled = true
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)
        enabled = false
        rule.waitForIdle()
        assertFalse(view.keepScreenOn)
    }
}
