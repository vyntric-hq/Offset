package com.vyntric.offset

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
            app.container.staging.put(emptyList())
        }
    }

    @Test fun readThenWriteThroughTheUi() {
        rule.onNodeWithText("Read radio").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Read again").fetchSemanticsNodes().isNotEmpty() }
        // The factory image has two channels; reading an empty staging list loads them.
        rule.onNodeWithText("Write 2 channels to radio").performClick()
        // Rule 6: the confirmation sheet names the radio and the channel count.
        rule.onNodeWithText("Ident").assertExists()
        rule.onNodeWithText("Write 2 channels").performClick()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("Written and verified").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performClick()
    }
}
