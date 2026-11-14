/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vyntric.offset.ui.components.BarAction
import com.vyntric.offset.ui.components.LargeTitleScreen
import com.vyntric.offset.ui.theme.OffsetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** At twice the normal font size the collapsed top bar keeps its title, back button and actions apart. */
@RunWith(AndroidJUnit4::class)
class LargeFontLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun collapsedTopBarNeverOverlapsAtDoubleFontSize() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                OffsetTheme(dark = false) {
                    LargeTitleScreen(
                        title = "Factory read UV-5R BFB291 2026-11-14",
                        modifier = Modifier.testTag("screen"),
                        onBack = {},
                        backLabel = "Images",
                        actions = { BarAction("Presets") {}; BarAction("Add") {} },
                    ) {
                        items(60) { Text("Row $it") }
                    }
                }
            }
        }
        // Scroll so the large title collapses into the bar.
        rule.onNodeWithTag("screen").performTouchInput { swipeUp() }
        rule.waitForIdle()

        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val back = rule.onNodeWithText("Images").getUnclippedBoundsInRoot()
        val presets = rule.onNodeWithText("Presets").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithText("Add").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText("Factory read UV-5R BFB291 2026-11-14", useUnmergedTree = true).getUnclippedBoundsInRoot()

        fun Dp.atMost(other: Dp) = this.value <= other.value + 0.5f
        assertTrue("Add is on screen", add.right.atMost(root.right))
        assertTrue("back button ends before the actions", back.right.atMost(presets.left))
        assertTrue("title starts after the back button", back.right.atMost(title.left))
        assertTrue("title ends before the actions", title.right.atMost(presets.left))
    }

    @Test fun barActionsKeepTheirFullSizeOnANarrowScreen() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                OffsetTheme(dark = false) {
                    Box(Modifier.width(320.dp)) {
                        LargeTitleScreen(
                            title = "Radio",
                            modifier = Modifier.testTag("screen"),
                            actions = { BarAction("Presets") {}; BarAction("Add") {} },
                        ) {
                            items(60) { Text("Row $it") }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("screen").performTouchInput { swipeUp() }
        rule.waitForIdle()

        // Before the fix the first action took the room and "Add" shrank to a clipped "A".
        for (label in listOf("Presets", "Add")) {
            val layout = textLayout(label)
            assertFalse("$label is not clipped", layout.hasVisualOverflow)
            assertEquals("$label keeps the body size", bodySize, layout.layoutInput.style.fontSize)
        }
    }

    private val bodySize = com.vyntric.offset.ui.theme.OffsetType().body.fontSize

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }
}
