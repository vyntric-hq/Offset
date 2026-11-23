/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyntric.offset.data.db.OffsetDatabase
import com.vyntric.offset.domain.reach.Catalog
import com.vyntric.offset.domain.reach.FarStation
import com.vyntric.offset.domain.reach.Feedline
import com.vyntric.offset.domain.reach.Place
import com.vyntric.offset.domain.reach.ReachBand
import com.vyntric.offset.domain.reach.RepeaterAssumptions
import com.vyntric.offset.domain.reach.Station
import com.vyntric.offset.domain.reach.StationLocation
import com.vyntric.offset.ui.reach.ReachScreen
import com.vyntric.offset.ui.theme.OffsetTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReachDeviceTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A database from Offset 0.3 (schema 2) opens with an empty Reach inventory, images intact. */
    @Test fun version2DatabaseGainsTheReachTable(): Unit = runBlocking {
        val name = "migration-2-3-test.db"
        ctx.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            V2_SCHEMA.forEach(db::execSQL)
            db.execSQL("INSERT INTO staging (id, json, updatedAt) VALUES (0, '[]', 1)")
            db.version = 2
        }
        val room = Room.databaseBuilder(ctx, OffsetDatabase::class.java, name).addMigrations(*OffsetDatabase.MIGRATIONS).build()
        val c = AppContainer(ctx, room)
        assertTrue(c.inventory.observe().first().stations.isEmpty())
        assertEquals(0, c.staging.get().size)
        room.close()
        ctx.deleteDatabase(name)
    }

    /** Radios, antennas, stations, places and assumptions survive a round trip through Room. */
    @Test fun inventoryRoundTrips(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, OffsetDatabase::class.java).build()
        val c = AppContainer(ctx, db)
        // A new station with new equipment is saved in one transaction; the ids are filled in.
        val stationId = c.inventory.saveStation(
            Station(name = "Beam", radioId = 0, antennaId = 0, watts = 25.0, heightM = 10.0,
                feedline = Feedline.LMR400, feedlineM = 15.0, bearingDeg = 45.0, location = StationLocation.Fixed(38.25, -85.76, "Home")),
            Catalog.radios.first { it.catalogKey == "mobile50" },
            Catalog.antennas.first { it.catalogKey == "yagi5-70" },
        )
        c.inventory.save(Place(name = "Alex", latitude = 38.3, longitude = -85.7, far = FarStation.MOBILE))
        c.inventory.save(RepeaterAssumptions(heightM = 60.0))
        c.inventory.save(RepeaterAssumptions(heightM = 45.0))

        val inv = c.inventory.observe().first()
        val setup = inv.setup(inv.stations.single { it.id == stationId })!!
        assertEquals(listOf(ReachBand.SEVENTY_CM), setup.bands) // the radio has 2 m and 70 cm; the beam only 70 cm
        assertEquals(StationLocation.Fixed(38.25, -85.76, "Home"), setup.station.location)
        assertEquals(FarStation.MOBILE, inv.places.single().far)
        assertTrue(inv.places.single().createdAt > 0)
        assertEquals(45.0, inv.repeaterAssumptions.heightM, 0.0)

        c.inventory.delete(stationId)
        assertTrue(c.inventory.observe().first().stations.isEmpty())
        db.close()
    }

    /**
     * First run on the Reach tab: the welcome card, then a station set up from the catalog in a few
     * taps (radio, antenna, a fixed spot typed as a grid square), and the station card appears.
     */
    @Test fun firstStationFromTheCatalog() {
        val db = Room.inMemoryDatabaseBuilder(ctx, OffsetDatabase::class.java).build()
        val c = AppContainer(ctx, db)
        rule.setContent { OffsetTheme(dark = false) { ReachScreen(c) } }

        // The inventory arrives from Room a moment after the first frame.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("See how far you reach").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Set up a station").performClick()
        rule.onAllNodesWithText("Choose")[0].performClick()
        rule.onNodeWithText("Baofeng UV-5R").performScrollTo().performClick()
        // Picking a radio goes straight on to antennas that suit it.
        rule.onNodeWithText("Stock rubber duck").performScrollTo().performClick()
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()

        rule.onNodeWithText("Fixed spot").performScrollTo().performClick()
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Where").performTextReplacement("EM78gh")
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()

        rule.waitUntil(5_000) { runBlocking { c.inventory.observe().first().stations.isNotEmpty() } }
        // The station card replaces the welcome card once the saved station flows back from Room.
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Who hears me", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Who I hear", useUnmergedTree = true).assertExists()
        val station = runBlocking { c.inventory.observe().first().stations.single() }
        assertEquals("UV-5R", station.name)
        assertTrue(station.location is StationLocation.Fixed)
        db.close()
    }

    private companion object {
        /** Schema 2 as Room created it (app/schemas/.../2.json). */
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`radio` TEXT NOT NULL DEFAULT 'uv5r', `identHex` TEXT NOT NULL, `model` TEXT NOT NULL, `firmware` TEXT, " +
                "`channelCount` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `bytes` BLOB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_images_identHex` ON `images` (`identHex`)",
            "CREATE INDEX IF NOT EXISTS `index_images_kind` ON `images` (`kind`)",
            "CREATE TABLE IF NOT EXISTS `staging` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '6d6eb5990e72f0fb81621a3a67582dce')",
        )
    }
}
