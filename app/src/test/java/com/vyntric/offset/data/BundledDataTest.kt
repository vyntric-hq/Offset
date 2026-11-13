package com.vyntric.offset.data

import com.vyntric.offset.data.repeaters.HearhamProvider
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
}
