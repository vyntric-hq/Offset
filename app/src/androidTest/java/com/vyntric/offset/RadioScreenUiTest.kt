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
import org.junit.Assert.assertEquals
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
        app.container.settings.update { it.copy(simulatedRadio = true, radioType = com.vyntric.offset.protocol.Uv5rDriver.id) }
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

    /** A set built over an image from Images saves with no radio read. */
    @Test fun setFromALoadedImageSavesWithoutARead() {
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OffsetApp).container
        runBlocking {
            val image = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("simulated/uv5r-factory.img")
                .use { com.vyntric.offset.protocol.ChirpImg.decode(it.readBytes()).image }
            // Another radio type is selected; loading the image selects the image's type.
            c.settings.update { it.copy(radioType = com.vyntric.offset.protocol.RadioDrivers.all.first { d -> d != image.driver }.id) }
            val id = c.images.save(image, "Base for UI test", com.vyntric.offset.domain.ImageKind.USER)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.vyntric.offset.ui.images.ImageDetailViewModel(c, id).loadIntoStaging()
            }
        }
        rule.waitUntil(10_000) { c.settings.settings.value.radioType == com.vyntric.offset.protocol.Uv5rDriver.id }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Base for UI test").fetchSemanticsNodes().size >= 2 }
        // The set takes the image's name; rename it.
        rule.onAllNodesWithText("Base for UI test")[0].performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Hike")
        rule.onNodeWithText("Done").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Hike").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Based on").assertExists()
        rule.onNodeWithText("Save to Images").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Saved to Images as \"Hike\".").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Already in Images as \"Hike\".").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** RT-950 Pro: rename a zone from the Radio tab and write it with the channels. */
    @Test fun renameAZoneAndWriteIt() {
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OffsetApp).container
        c.settings.update { it.copy(radioType = com.vyntric.offset.protocol.Rt950ProDriver.id) }
        rule.onNodeWithText("Read radio").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Write 6 channels to radio").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("1 · ZONEONE").assertExists()
        rule.onNodeWithText("Zones").performClick()
        rule.onNodeWithText("1 · ZoneOne").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Repeaters")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("1 · Repeaters").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("1 · REPEATERS").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Write 6 channels to radio").performClick()
        rule.onNodeWithText("1 renamed").assertExists()
        rule.onNodeWithText("Write 6 channels").performClick()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("Written and verified").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performClick()
        assertEquals("Repeaters", c.radioSession.last.value!!.image.namedZones!!.names[0])
    }
}
