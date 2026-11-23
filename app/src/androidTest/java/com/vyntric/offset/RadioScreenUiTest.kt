package com.vyntric.offset

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real UI: read the simulated radio, write staging back, see the verified result. */
@RunWith(AndroidJUnit4::class)
class RadioScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun simulate() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OffsetApp
        app.container.settings.update { it.copy(simulatedRadio = true) }
        runBlocking {
            app.container.simulatedLink.reset()
            app.container.staging.clear()
            app.container.radioSession.clear()
        }
    }

    @Test fun readThenWriteThroughTheUi() {
        rule.onNodeWithText("Read radio").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Read again").fetchSemanticsNodes().isNotEmpty() }
        // The factory image has two channels; reading an empty staging list loads them, just after the read.
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Write 2 channels to radio").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Write 2 channels to radio").performClick()
        // Rule 6: the confirmation sheet names the radio and the channel count.
        rule.onNodeWithText("Ident").assertExists()
        rule.onNodeWithText("Write 2 channels").performClick()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("Written and verified").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performClick()
    }

    @Test fun namedSetSavesToImagesOnce() {
        rule.onNodeWithText("Read radio").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Save to Images").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Untitled").performClick()
        rule.onNodeWithText("Set name").assertExists()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Field Day")
        rule.onNodeWithText("Done").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Field Day").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save to Images").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Saved to Images as \"Field Day\".").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save to Images").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Already in Images as \"Field Day\".").fetchSemanticsNodes().isNotEmpty() }
    }
}
