package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** The RT-950 Pro driver through its public interfaces, against the simulated radio. */
class Rt950ProDriverTest {
    private val driver: RadioDriver = Rt950ProDriver
    private val sample = Rt950ProDriver.sampleImage()

    private fun radio(image: RadioImage = sample) = driver.simulator(image) as Rt950ProSimulator

    private fun clone(radio: SerialPort, seed: Int = 1, progress: MutableList<CloneProgress> = mutableListOf()) =
        driver.clone(radio, Sleeper.NONE, { progress += it }, Random(seed))

    private inline fun <reified T : RadioException> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: RadioException) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun `download reproduces the radio image and ends the session`() {
        val sim = radio()
        val progress = mutableListOf<CloneProgress>()
        val read = clone(sim, progress = progress).download()
        assertEquals(sample, read)
        assertEquals(CloneProgress.Phase.IDENTIFYING, progress.first().phase)
        assertEquals(Rt950ProImage.SIZE, progress.last().bytesDone)
        assertEquals(1, sim.sessionsEnded)
    }

    @Test fun `download works with every key the radio can pick`() {
        val sim = radio()
        repeat(40) { seed ->
            assertEquals(sample, clone(sim, seed).download())
        }
    }

    @Test fun `upload of an edit reads back equal`() {
        val sim = radio()
        val before = clone(sim).download() as Rt950ProImage
        val target = before.withChannels(
            before.channels().toMutableList().also {
                it[0] = it[0]!!.copy(name = "CALL 146")
                it[9] = Channel(147_000_000, Duplex.PLUS, 600_000, Squelch.Ctcss(1000), name = "New")
                it[959] = Channel(439_000_000, Duplex.MINUS, 7_600_000, power = Power.LOW)
                it[4] = null
            },
        )
        val progress = mutableListOf<CloneProgress>()
        assertEquals(240, clone(sim, progress = progress).upload(target, before))
        assertEquals(240, sim.blocksWritten)
        assertEquals(Rt950ProImage.CHANNELS.length, progress.last().bytesDone)
        val after = clone(sim).download()
        assertEquals(target, after)
        assertTrue(target.mismatchedChannels(after).isEmpty())
        assertEquals("CALL 146", after.channel(0)!!.name)
        assertNull(after.channel(4))
        assertEquals(sample.channel(5), after.channel(5)) // the AM channel is untouched
        assertEquals(3, sim.sessionsEnded)
    }

    @Test fun `unchanged image writes the same bytes`() {
        val sim = radio()
        val before = clone(sim).download() as Rt950ProImage
        assertEquals(240, clone(sim).upload(before, before))
        assertEquals(before, sim.currentImage())
    }

    @Test fun `silent radio is no contact`() {
        val sim = radio().apply { silent = true }
        expect<RadioException.NoContact> { clone(sim).download() }
    }

    @Test fun `wrong model is refused before anything is written`() {
        val sim = Rt950ProSimulator(sample, model = "UV-K5")
        val e = expect<RadioException.IdentMismatch> { clone(sim).upload(sample.withChannel(0, null), sample) }
        assertTrue(e.message!!.contains("UV-K5"))
        assertEquals(0, sim.blocksWritten)
        expect<RadioException.IdentMismatch> { clone(Rt950ProSimulator(sample, model = "")).download() }
    }

    @Test fun `cable pulled mid write is a disconnect`() {
        val sim = radio()
        val before = clone(sim).download() as Rt950ProImage
        val handshake = 14 + 1 + 1 + Rt950ProCipher.FRAME_SIZE
        sim.detachAfterHostBytes = sim.hostBytesReceived + handshake + 132 * 10 + 50
        expect<RadioException.Disconnected> { clone(sim).upload(before.withChannel(100, Channel(146_000_000)), before) }
        assertEquals(10, sim.blocksWritten)
        sim.reattach()
        assertEquals(before, clone(sim).download())
    }

    @Test fun `a corrupted block is caught by comparing a read back`() {
        val sim = radio().apply { corruptWriteAddresses = setOf(0x0080) }
        val before = clone(sim).download() as Rt950ProImage
        val target = before.withChannel(4, Channel(146_550_000, name = "SIMPLEX"))
        clone(sim).upload(target, before)
        val after = clone(sim).download()
        assertEquals(listOf(4), target.mismatchedChannels(after))
    }

    @Test fun `upload refuses an image that changes settings and sends nothing`() {
        val sim = radio()
        val bytes = sample.bytes().also { it[Rt950ProImage.SEGMENTS[1].imageOffset] = 4 }
        expect<RadioException.Unsupported> { clone(sim).upload(Rt950ProImage.of(bytes), sample) }
        assertEquals(0L, sim.hostBytesReceived)
    }

    @Test fun `radio that stops mid read times out`() {
        val sim = radio()
        val port = object : SerialPort by sim {
            var reads = 0
            override fun read(count: Int, timeoutMs: Long): ByteArray =
                if (++reads > 20) ByteArray(0) else sim.read(count, timeoutMs)
        }
        expect<RadioException.Timeout> { clone(port).download() }
    }

    @Test fun `image recognition, metadata and chirp img round trip`() {
        assertSame(Rt950ProDriver, RadioDrivers.recognize(sample.bytes()))
        assertFalse(driver.recognizes(ByteArray(Rt950ProImage.SIZE - 1)))
        assertFalse(driver.recognizes(Fixtures.uv5rFactory().bytes()))
        assertEquals(mapOf("rclass" to "RT950Pro", "vendor" to "Radtel", "model" to "RT-950 Pro", "variant" to ""), sample.chirpMetadata)
        assertEquals("RT-950 Pro", sample.identity)
        val parsed = ChirpImg.decode(ChirpImg.encode(sample))
        assertEquals(sample, parsed.image)
        assertSame(Rt950ProDriver, RadioDrivers.image(null, sample.bytes()).driver)
        try {
            driver.image(ByteArray(100)); fail()
        } catch (_: InvalidImageException) {
        }
    }

    @Test fun `sample image holds a few channels and plausible settings`() {
        assertEquals(6, sample.usedChannelCount)
        assertEquals(Channel(146_520_000, name = "CALLING"), sample.channel(0))
        assertEquals(Duplex.OFF, sample.channel(5)!!.duplex)
        val vfo = sample.bytes().copyOfRange(Rt950ProImage.SEGMENTS[1].imageOffset, Rt950ProImage.SEGMENTS[1].imageOffset + 8)
        assertArrayEquals(byteArrayOf(1, 4, 6, 5, 2, 0, 0, 0), vfo)
        for (c in sample.channels().filterNotNull()) {
            assertEquals(c.toString(), emptyList<ChannelValidator.Problem>(), ChannelValidator.validate(c, sample.profile))
        }
    }

    @Test fun `only channel memory is writable`() {
        val edited = sample.withChannels(listOf(Channel(144_390_000, name = "APRS")))
        assertTrue(sample.differsOnlyInWritableRegions(edited))
        assertEquals((0 until 6).toList(), sample.mismatchedChannels(edited))
        val settings = Rt950ProImage.of(sample.bytes().also { it[Rt950ProImage.SIZE - 1] = 0 })
        assertFalse(sample.differsOnlyInWritableRegions(settings))
        val restored = settings.withWritableRegionsOf(edited)
        assertEquals(edited.channels(), restored.channels())
        assertNotEquals(edited, restored)
        assertEquals(settings.bytes()[Rt950ProImage.SIZE - 1], restored.bytes()[Rt950ProImage.SIZE - 1])
    }

    @Test fun `withChannels keeps unchanged slots byte for byte`() {
        assertEquals(sample, sample.withChannels(sample.channels()))
        val emptied = sample.withChannels(emptyList())
        assertEquals(0, emptied.usedChannelCount)
        assertTrue(emptied.bytes().copyOfRange(0, 0x7800).all { it == 0xFF.toByte() })
    }

    @Test fun `profile`() {
        val p = driver.defaultProfile
        assertEquals(listOf(Power.HIGH, Power.MID, Power.LOW), p.powerLevels)
        assertEquals(12, p.nameLength)
        assertFalse(p.uppercaseNames)
        assertTrue(p.inBand(29_600_000) && p.inBand(446_000_000) && !p.inBand(66_000_000) && !p.inBand(580_000_000))
        assertEquals(10.0, p.watts(Power.HIGH), 0.0)
    }
}
