package com.vyntric.offset.domain

import com.vyntric.offset.AppFixtures
import com.vyntric.offset.MemoryLibrary
import com.vyntric.offset.protocol.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveNamedImageTest {
    private val library = MemoryLibrary()
    private val save = SaveNamedImage(library)
    private val factory = AppFixtures.factoryImage()
    private val a = factory.withChannels(listOf(Channel(146_520_000, name = "CALL")))
    private val b = factory.withChannels(listOf(Channel(146_550_000, name = "S1")))

    private fun names() = library.items.values.map { it.name }

    @Test fun `a new name is saved as given`() = runTest {
        val r = save(a, "Trip")
        assertEquals(SaveNamedImage.Saved(r.id, "Trip", existing = false), r)
        assertEquals(listOf("Trip"), names())
    }

    @Test fun `an identical image under the name is not saved again`() = runTest {
        val first = save(a, "Trip")
        val again = save(a, "Trip")
        assertEquals(SaveNamedImage.Saved(first.id, "Trip", existing = true), again)
        assertEquals(1, library.items.size)
    }

    @Test fun `a different image under the name gets the next free number`() = runTest {
        save(a, "Trip")
        assertEquals("Trip (2)", save(b, "Trip").name)
        assertEquals("Trip (3)", save(factory, "Trip").name)
        assertEquals(listOf("Trip", "Trip (2)", "Trip (3)"), names())
    }

    @Test fun `a match anywhere in the numbered family counts as identical`() = runTest {
        save(a, "Trip")
        val second = save(b, "Trip")
        val again = save(b, "Trip")
        assertEquals(SaveNamedImage.Saved(second.id, "Trip (2)", existing = true), again)
        assertEquals(2, library.items.size)
    }

    @Test fun `only the channels count, not settings the radio changes itself`() = runTest {
        val first = save(a, "Trip")
        // A settings byte (UV-5R settings block at 0x0E20) differs, as after the radio is used.
        val bytes = a.bytes().also { it[0x0E2A] = (it[0x0E2A] + 1).toByte() }
        val sameChannelsOtherSettings = com.vyntric.offset.protocol.Uv5rImage.of(bytes)
        assertFalse(sameChannelsOtherSettings == a)
        val again = save(sameChannelsOtherSettings, "Trip")
        assertTrue(again.existing)
        assertEquals(first.id, again.id)
    }

    @Test fun `a blank name is saved as Untitled`() = runTest {
        assertEquals("Untitled", save(a, "   ").name)
        assertEquals("Untitled (2)", save(b, "").name)
    }

    @Test fun `a name that already ends in a number continues from its base`() = runTest {
        save(a, "Trip")
        save(b, "Trip")
        assertEquals("Trip (3)", save(factory, "Trip (2)").name)
    }

    @Test fun `backups and factory images with the name are ignored`() = runTest {
        library.save(a, "Trip", ImageKind.BACKUP)
        library.save(b, "Trip", ImageKind.FACTORY)
        val r = save(a, "Trip")
        assertFalse(r.existing)
        assertEquals("Trip", r.name)
    }

    @Test fun `concurrent saves of the same image store it once`() = runTest {
        val results = List(5) { async { save(a, "Trip") } }.awaitAll()
        assertEquals(1, library.items.size)
        assertEquals(1, results.count { !it.existing })
    }
}
