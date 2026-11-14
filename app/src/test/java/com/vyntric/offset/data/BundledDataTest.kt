package com.vyntric.offset.data

import com.vyntric.offset.data.repeaters.HearhamProvider
import com.vyntric.offset.data.repeaters.BundledListings
import com.vyntric.offset.domain.DirectoryMerge
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.Service
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Checks the repeater snapshot that ships in the APK (app/src/main/assets/repeaters). */
class BundledDataTest {
    private val dir = File("src/main/assets/repeaters")

    @Test fun `the shipped snapshot parses and matches its metadata`() {
        val meta = Json.parseToJsonElement(File(dir, "hearham.meta.json").readText()).jsonObject
        val records = meta["records"]!!.jsonPrimitive.long.toInt()
        val fetchedAt = meta["fetchedAt"]!!.jsonPrimitive.long
        assertTrue("snapshot date looks wrong", fetchedAt > 1_767_225_600_000L) // after 2026-01-01
        val parsed = GZIPInputStream(File(dir, "hearham.json.gz").inputStream()).use { HearhamProvider.parse(it) }
        assertTrue("expected a nationwide list, got ${parsed.size}", parsed.size > 10_000)
        // The script filters loosely; the app's parser may drop a few more (for example bad tones).
        assertTrue("parser kept ${parsed.size} of $records", parsed.size >= records * 0.98)
        assertTrue(parsed.all { it.service == Service.GMRS || it.band != null })
        assertTrue("most entries should have coordinates", parsed.count { it.latitude != null } > parsed.size * 0.9)
        assertEquals("ids are unique", parsed.size, parsed.map { it.id }.toSet().size)
    }

    @Test fun `the shipped IRLP and OpenStreetMap listings parse and corroborate Hearham`() {
        val meta = Json.parseToJsonElement(File(dir, "listings.meta.json").readText()).jsonObject
        val expected = meta.values.sumOf { it.jsonObject["records"]!!.jsonPrimitive.long.toInt() }
        val listings = GZIPInputStream(File(dir, "listings.json.gz").inputStream()).use { BundledListings.parse(it) }
        assertTrue("parser kept ${listings.size} of $expected", listings.size >= expected * 0.98)
        assertTrue(listings.any { it.source == RepeaterSource.IRLP } && listings.any { it.source == RepeaterSource.OPENSTREETMAP })
        assertEquals("ids are unique", listings.size, listings.map { it.id }.toSet().size)
        val hearham = GZIPInputStream(File(dir, "hearham.json.gz").inputStream()).use { HearhamProvider.parse(it) }
        val merged = DirectoryMerge.merge(hearham.map(HearhamProvider::withOrigin), listings)
        val corroborated = merged.count { it.verification.corroborated }
        val added = merged.size - hearham.size
        println("Bundled: ${hearham.size} Hearham, ${listings.size} listings, $corroborated corroborated, $added added")
        val matched = merged.count { it.listings.size > 1 }
        println("Bundled: $matched Hearham entries matched by a listing")
        assertTrue("most IRLP nodes should match a Hearham entry", matched > 400)
        assertTrue("copies of IRLP nodes in Hearham must not count as independent", corroborated < matched)
        // Every listing is merged or added, except unmatched ones with no input frequency, which only corroborate.
        val kept = merged.sumOf { it.listings.size } - hearham.size
        assertTrue("no programmable listing is lost", kept >= listings.count { it.inputHz != null })
        assertTrue(kept <= listings.size)
    }
}
