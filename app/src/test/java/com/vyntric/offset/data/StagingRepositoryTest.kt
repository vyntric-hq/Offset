package com.vyntric.offset.data

import com.vyntric.offset.data.db.StagingDao
import com.vyntric.offset.data.db.StagingEntity
import com.vyntric.offset.domain.ChannelOrigin
import com.vyntric.offset.domain.StagedChannel
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Modulation
import com.vyntric.offset.protocol.Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

class StagingRepositoryTest {
    private class MemoryDao : StagingDao {
        val row = MutableStateFlow<StagingEntity?>(null)
        override fun observe(): Flow<StagingEntity?> = row
        override suspend fun get(): StagingEntity? = row.value
        override suspend fun put(entity: StagingEntity) { row.value = entity }
    }

    @Test fun `the set name survives list changes and clears with the list`() = runTest {
        val repo = StagingRepository(MemoryDao(), Clock.systemUTC())
        assertEquals("", repo.name())
        repo.setName("  Bay Area ")
        assertEquals("Bay Area", repo.name())
        assertTrue(repo.get().isEmpty())
        val ch = StagedChannel(1, Channel(146_520_000, name = "CALL"), ChannelOrigin.MANUAL, slot = 0)
        repo.put(listOf(ch))
        repo.update { it + it.single().copy(key = 2, slot = 1) to Unit }
        assertEquals("Bay Area", repo.name())
        assertEquals(2, repo.get().size)
        repo.put(listOf(ch), "Trip", baseImageId = null)
        assertEquals("Trip", repo.name())
        repo.clear()
        assertEquals("", repo.name())
        assertTrue(repo.get().isEmpty())
    }

    @Test fun `the loaded image stays the base through edits, renames and clear`() = runTest {
        val dao = MemoryDao()
        val repo = StagingRepository(dao, Clock.systemUTC())
        val ch = StagedChannel(1, Channel(146_520_000, name = "CALL"), ChannelOrigin.IMAGE, slot = 0)
        repo.put(listOf(ch), "Trip", baseImageId = 7)
        assertEquals(7L, repo.observeBaseImageId().first())
        repo.update { emptyList<StagedChannel>() to Unit }
        repo.setName("Camp")
        repo.clear()
        repo.put(listOf(ch))
        assertEquals(7L, repo.observeBaseImageId().first())
        // Refilling staging from a radio read forgets the base, and only then.
        assertEquals(false, repo.update(forgetBaseIf = { it }) { it to false })
        assertEquals(7L, repo.observeBaseImageId().first())
        repo.update(forgetBaseIf = { it }) { listOf(ch) to true }
        assertEquals(null, repo.observeBaseImageId().first())
    }

    @Test fun `mode, new power levels and radio options survive a save`() = runTest {
        val dao = MemoryDao()
        val repo = StagingRepository(dao, Clock.systemUTC())
        val hf = Channel(
            7_074_000, Duplex.OFF, mode = Modulation.USB, power = Power.VERY_HIGH, name = "FT8",
            options = mapOf("nicfw.groups" to "AC", "nicfw.clarifier" to "-1.25"),
        )
        repo.put(listOf(StagedChannel(1, hf, ChannelOrigin.MANUAL, slot = 3)))
        assertEquals(hf, repo.get().single().channel)
    }

    @Test fun `lists saved before modes existed read as FM without options`() = runTest {
        val dao = MemoryDao()
        dao.row.value = StagingEntity(
            json = """[{"key":1,"origin":"MANUAL","rx":146520000,"duplex":"SIMPLEX","offset":0,"tx":"","rxTone":"",""" +
                """"power":"HIGH","wide":true,"scan":true,"bcl":false,"pttId":"OFF","pttIdCode":0,"name":"Calling","slot":0}]""",
            updatedAt = 0,
        )
        val ch = StagingRepository(dao, Clock.systemUTC()).get().single().channel
        assertEquals(Modulation.FM, ch.mode)
        assertEquals(emptyMap<String, String>(), ch.options)
        assertTrue(StagingRepository(dao, Clock.systemUTC()).get().single().modeUnknown)
    }

    @Test fun `an unknown mode survives saving the list again before a read`() = runTest {
        val dao = MemoryDao()
        val repo = StagingRepository(dao, Clock.systemUTC())
        repo.put(listOf(StagedChannel(1, Channel(127_550_000, Duplex.OFF), ChannelOrigin.RADIO, modeUnknown = true)))
        repo.update { it + StagedChannel(2, Channel(146_520_000), ChannelOrigin.MANUAL, slot = 1) to Unit }
        assertEquals(listOf(true, false), repo.get().map { it.modeUnknown })
    }

    @Test fun `channels saved with a mode are not mode unknown`() = runTest {
        val repo = StagingRepository(MemoryDao(), Clock.systemUTC())
        repo.put(listOf(StagedChannel(1, Channel(146_520_000), ChannelOrigin.MANUAL)))
        assertFalse(repo.get().single().modeUnknown)
    }

    @Test fun `zones are kept through edits, renames and clear, and set with a load or a read`() = runTest {
        val repo = StagingRepository(MemoryDao(), Clock.systemUTC())
        assertEquals(null, repo.zones())
        val zones = com.vyntric.offset.domain.StagedZones.of(com.vyntric.offset.protocol.Rt950ProDriver.sampleImage())!!
        val ch = StagedChannel(1, Channel(146_520_000, name = "CALL"), ChannelOrigin.IMAGE, slot = 0)
        repo.put(listOf(ch), "Trip", baseImageId = 3, zones = zones)
        repo.setName("Camp")
        repo.updateZones { it?.renamed(2, "CB")?.copy(addTo = 2) }
        repo.update { it to Unit }
        repo.clear()
        val kept = repo.zones()!!
        assertEquals("CB", kept.names[2])
        assertEquals(2, kept.addTo)
        assertEquals(kept, repo.observeZones().first())
        repo.update(zonesFor = { _, _ -> null }) { listOf(ch) to Unit }
        assertEquals(null, repo.zones())
    }
}
