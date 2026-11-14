package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Squelch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryMergeTest {
    private fun rep(
        source: RepeaterSource, call: String, hz: Long, lat: Double?, lon: Double?,
        tone: Squelch = Squelch.Ctcss(1000), seen: Long? = null,
    ) = Repeater(
        "${source.name}:$call:$hz", call, hz, hz - 600_000, tone, Squelch.None, "Town", null, "CA", lat, lon, source,
        listings = listOf(Listing(source, tone, lastReportMillis = seen)),
    )

    private val hearham = rep(RepeaterSource.HEARHAM, "W6ABC", 146_940_000, 37.30, -121.90)

    @Test fun `same frequency and site corroborates even under another callsign`() {
        val irlp = rep(RepeaterSource.IRLP, "K6CLUB", 146_940_000, 37.35, -121.95, seen = 42L)
        val merged = DirectoryMerge.merge(listOf(hearham), listOf(irlp))
        assertEquals(1, merged.size)
        val v = merged.single().verification
        assertEquals(listOf(RepeaterSource.HEARHAM, RepeaterSource.IRLP), v.sources)
        assertTrue(v.corroborated)
        assertEquals(42L, v.lastReportMillis)
        assertFalse(v.tonesDisagree)
        assertEquals("primary values win", "W6ABC", merged.single().callsign)
    }

    @Test fun `same callsign matches further away but not across the country`() {
        val near = rep(RepeaterSource.OPENSTREETMAP, "W6ABC", 146_940_000, 37.90, -121.90) // about 67 km
        val far = rep(RepeaterSource.OPENSTREETMAP, "W6ABC", 146_940_000, 40.00, -105.00)
        assertEquals(1, DirectoryMerge.merge(listOf(hearham), listOf(near)).size)
        assertEquals(2, DirectoryMerge.merge(listOf(hearham), listOf(far)).size)
    }

    @Test fun `different frequency or distant site stays separate and is credited to its own directory`() {
        val otherFreq = rep(RepeaterSource.IRLP, "W6ABC", 146_970_000, 37.30, -121.90)
        val otherSite = rep(RepeaterSource.IRLP, "K6XYZ", 146_940_000, 36.60, -121.90) // about 78 km
        val merged = DirectoryMerge.merge(listOf(hearham), listOf(otherFreq, otherSite))
        assertEquals(3, merged.size)
        assertTrue(merged.drop(1).all { it.verification.sources == listOf(RepeaterSource.IRLP) && !it.verification.corroborated })
    }

    @Test fun `without coordinates only a matching callsign counts, and coordinates are filled in`() {
        val unplaced = hearham.copy(latitude = null, longitude = null)
        val sameCall = rep(RepeaterSource.IRLP, "W6ABC", 146_940_000, 37.31, -121.91)
        val otherCall = rep(RepeaterSource.IRLP, "K6XYZ", 146_940_000, 37.31, -121.91)
        val merged = DirectoryMerge.merge(listOf(unplaced), listOf(sameCall))
        assertEquals(1, merged.size)
        assertEquals(37.31, merged.single().latitude!!, 1e-9)
        assertEquals(2, DirectoryMerge.merge(listOf(unplaced), listOf(otherCall)).size)
    }

    @Test fun `secondary directories corroborate each other and conflicting tones are flagged`() {
        val irlp = rep(RepeaterSource.IRLP, "K6NEW", 445_500_000, 34.0, -118.0, tone = Squelch.Ctcss(1000))
        val osm = rep(RepeaterSource.OPENSTREETMAP, "K6NEW", 445_500_000, 34.01, -118.01, tone = Squelch.Ctcss(1273))
        val merged = DirectoryMerge.merge(listOf(hearham), listOf(irlp, osm))
        assertEquals(2, merged.size)
        val v = merged.last().verification
        assertEquals(listOf(RepeaterSource.IRLP, RepeaterSource.OPENSTREETMAP), v.sources)
        assertTrue(v.tonesDisagree)
    }

    @Test fun `a listing without a tone does not count as disagreement`() {
        val noTone = rep(RepeaterSource.IRLP, "W6ABC", 146_940_000, 37.30, -121.90, tone = Squelch.None)
        assertFalse(DirectoryMerge.merge(listOf(hearham), listOf(noTone)).single().verification.tonesDisagree)
    }

    @Test fun `a copy of another directory's entry is listed but does not corroborate`() {
        val copied = hearham.copy(listings = hearham.listings.map { it.copy(origin = RepeaterSource.IRLP) })
        val irlp = rep(RepeaterSource.IRLP, "W6ABC", 146_940_000, 37.30, -121.90, seen = 7L)
        val v = DirectoryMerge.merge(listOf(copied), listOf(irlp)).single().verification
        assertEquals(listOf(RepeaterSource.HEARHAM, RepeaterSource.IRLP), v.sources)
        assertEquals(1, v.independentSources)
        assertFalse(v.corroborated)
        assertEquals("the node's own report still shows", 7L, v.lastReportMillis)
        val osm = rep(RepeaterSource.OPENSTREETMAP, "W6ABC", 146_940_000, 37.30, -121.90)
        assertTrue(DirectoryMerge.merge(listOf(copied), listOf(irlp, osm)).single().verification.corroborated)
    }

    @Test fun `a secondary record without an input frequency only corroborates`() {
        val noInput = rep(RepeaterSource.OPENSTREETMAP, "K6NEW", 445_500_000, 34.0, -118.0).copy(inputHz = null)
        assertEquals(listOf(hearham), DirectoryMerge.merge(listOf(hearham), listOf(noInput)))
        val matching = rep(RepeaterSource.OPENSTREETMAP, "W6ABC", 146_940_000, 37.30, -121.90).copy(inputHz = null)
        assertTrue(DirectoryMerge.merge(listOf(hearham), listOf(matching)).single().verification.corroborated)
    }

    @Test fun `a different input frequency is a different station`() {
        val simplexNode = rep(RepeaterSource.IRLP, "W6ABC", 146_940_000, 37.30, -121.90).copy(inputHz = 146_940_000)
        assertEquals(2, DirectoryMerge.merge(listOf(hearham), listOf(simplexNode)).size)
    }

    @Test fun `a located match wins over an unlocated one with the same callsign`() {
        val unplaced = hearham.copy(id = "unplaced", latitude = null, longitude = null)
        val irlp = rep(RepeaterSource.IRLP, "W6ABC", 146_940_000, 37.31, -121.91)
        val merged = DirectoryMerge.merge(listOf(unplaced, hearham), listOf(irlp))
        assertEquals(1, merged.first { it.id == "unplaced" }.listings.size)
        assertEquals(2, merged.first { it.id == hearham.id }.listings.size)
    }

    @Test fun `empty inputs`() {
        assertEquals(listOf(hearham), DirectoryMerge.merge(listOf(hearham), emptyList()))
        assertEquals(1, DirectoryMerge.merge(emptyList(), listOf(hearham)).size)
        assertNull(hearham.verification.lastReportMillis)
    }
}
