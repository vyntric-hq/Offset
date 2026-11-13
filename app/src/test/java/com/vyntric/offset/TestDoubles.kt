package com.vyntric.offset

import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.domain.ImageLibrary
import com.vyntric.offset.domain.LinkUnavailableException
import com.vyntric.offset.domain.RadioLink
import com.vyntric.offset.domain.StoredImage
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.SerialPort
import com.vyntric.offset.protocol.SimulatedRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Transport boundary: a RadioLink backed by the protocol module's SimulatedRadio. */
class SimLink(var radio: SimulatedRadio) : RadioLink {
    var unplugged = false
    var sessions = 0
    /** Runs before each session; lets a test swap radios or inject faults between sessions. */
    var beforeSession: (Int) -> Unit = {}
    override val cable: StateFlow<CableState> = MutableStateFlow(CableState.Simulated("test"))
    override fun requestPermission() = Unit
    override suspend fun <T> session(block: (SerialPort) -> T): T {
        beforeSession(sessions++)
        if (unplugged) throw LinkUnavailableException("No programming cable found.")
        return try { block(radio) } finally { radio.endSession() }
    }
}

/** Storage boundary: an in-memory image library. */
class MemoryLibrary : ImageLibrary {
    val items = LinkedHashMap<Long, StoredImage>()
    private var next = 1L
    var failSaves = false
    /** Fail only the n-th save (1 based), for example the result saved after a write. */
    var failSaveNumber: Int? = null
    var failHasFactory = false
    private var saves = 0
    override suspend fun save(image: RadioImage, name: String, kind: ImageKind, notes: String): Long {
        saves++
        if (failSaves || saves == failSaveNumber) throw java.io.IOException("disk full")
        val id = next++
        items[id] = StoredImage(id, name, kind, notes, 0, 0, image)
        return id
    }
    override suspend fun load(id: Long) = items[id]
    override suspend fun hasFactoryImage(identHex: String) =
        if (failHasFactory) throw IllegalStateException("database locked") else items.values.any { it.kind == ImageKind.FACTORY && it.image.identHex == identHex }
}

val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)

object AppFixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(AppFixtures::class.java.getResourceAsStream("/$name")) { "missing $name" }.use { it.readBytes() }

    /** The factory UV-5R image bundled as the simulated radio. */
    fun factoryImage(): RadioImage = RadioImage.of(
        requireNotNull(AppFixtures::class.java.getResourceAsStream("/fixtures/baoclone-uv5r-factory.img")).use { it.readBytes() }
    )
}
