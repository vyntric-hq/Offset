package com.vyntric.offset

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyntric.offset.data.db.OffsetDatabase
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.domain.ImportImage
import com.vyntric.offset.domain.RepeaterQuery
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.WriteRadio
import com.vyntric.offset.domain.toSlots
import com.vyntric.offset.protocol.Band
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChirpImg
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.Uv5rDriver
import com.vyntric.offset.protocol.Uv5rImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole programming flow on a device, against the simulated radio, with the real Room
 * database, file caches, CSV provider and use cases:
 * read, search (CSV), add to staging, write, verify, image saved, export .img, import .img, restore.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndSimulatedTest {
    private val csv = """
        Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment,Callsign,Latitude,Longitude,State
        0,K6ABC,145.230000,-,0.600000,Tone,100.0,88.5,023,NN,FM,5.00,,San Jose,K6ABC,37.33,-121.89,CA
        1,W6XYZ,444.500000,+,5.000000,TSQL,88.5,123.0,023,NN,FM,5.00,,Santa Clara,W6XYZ,37.35,-121.95,CA
        2,N6FAR,147.000000,+,0.600000,Tone,141.3,88.5,023,NN,FM,5.00,,Sacramento,N6FAR,38.58,-121.49,CA
    """.trimIndent()

    @Test fun fullProgrammingFlow() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(ctx, OffsetDatabase::class.java).build()
        val c = AppContainer(ctx, db)
        c.settings.update { it.copy(simulatedRadio = true, providers = setOf(RepeaterSource.CSV)) }
        c.simulatedLink.reset()

        // Read: the first read saves a factory image.
        c.settings.update { it.copy(radioType = Uv5rDriver.id) }
        val read = c.readRadio {}
        assertEquals(RadioModel.UV5R, (read.image as Uv5rImage).model)
        assertNotNull(read.factoryImageId)
        assertEquals(ImageKind.FACTORY, c.images.load(read.factoryImageId!!)!!.kind)

        // Search the imported CSV near San Jose (two within 25 miles, Sacramento is not).
        c.csvProvider.import(csv, "bay.csv")
        val found = c.csvProvider.search(RepeaterQuery.Near(37.33, -121.89, 40.2, com.vyntric.offset.domain.RepeaterFilters()))
        assertEquals(listOf("K6ABC", "W6XYZ"), found.repeaters.map { it.callsign })

        // Stage: a simplex channel plus the two repeaters.
        val target = c.stagingTarget()
        val base = c.buildStaging.add(emptyList(), listOf(Channel(146_520_000, name = "CALL")), com.vyntric.offset.domain.ChannelOrigin.MANUAL, target).staging
        c.staging.put(c.buildStaging.addRepeaters(base, found.repeaters, target).staging)
        val staged = c.staging.observe().first()
        assertEquals(3, staged.size)

        // Write with all safety rules, verify, save.
        val outcome = c.writeRadio(
            WriteRadio.Request(WriteRadio.Content.Channels(staged.toSlots(target.capacity)), "Bay Area", read.image.identity, read.image.driver),
        ) {}
        assertTrue(outcome.toString(), outcome is WriteRadio.Outcome.Verified)
        outcome as WriteRadio.Outcome.Verified
        val saved = c.images.load(outcome.savedImageId!!)!!
        assertEquals("Bay Area", saved.name)
        assertEquals(staged.map { it.channel }, saved.image.channels().take(3))
        assertEquals(read.image, c.images.load(outcome.backupImageId)!!.image)
        assertTrue(read.image.differsOnlyInWritableRegions(saved.image))

        // Export as CHIRP .img and import it back.
        val file = c.exportImage.img(saved.image)
        assertEquals(saved.image, ChirpImg.decode(file).image)
        val imported = c.importImage(file, "Bay Area.img") as ImportImage.Outcome.Saved
        val importedImage = c.images.load(imported.id)!!.image

        // Change the radio, then restore the imported image to it.
        val other = c.writeRadio(
            WriteRadio.Request(WriteRadio.Content.Channels(listOf(Channel(147_555_000, name = "TEMP"))), "Temp", read.image.identity, Uv5rDriver),
        ) {}
        assertTrue(other is WriteRadio.Outcome.Verified)
        val restored = c.writeRadio(
            WriteRadio.Request(WriteRadio.Content.FromImage(importedImage), "Restored", read.image.identity, importedImage.driver),
        ) {}
        assertTrue(restored.toString(), restored is WriteRadio.Outcome.Verified)
        val now = c.images.load((restored as WriteRadio.Outcome.Verified).savedImageId!!)!!.image
        assertEquals(importedImage.channels(), now.channels())

        c.settings.update { it.copy(simulatedRadio = false, providers = setOf(RepeaterSource.HEARHAM)) }
        c.csvProvider.clearCache()
        db.close()
    }
}
