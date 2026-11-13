package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureImageTest {
    @Test fun `identifies model and firmware of each fixture`() {
        Fixtures.chirpUv5r().let {
            assertEquals(RadioModel.UV5R, it.model)
            assertEquals("Ver BFB231", it.firmwareVersion)
            assertTrue(it.isOriginalFirmware)
        }
        Fixtures.uv5rFactory().let {
            assertEquals(RadioModel.UV5R, it.model)
            assertEquals("Ver BFB291", it.firmwareVersion)
            assertFalse(it.isOriginalFirmware)
            assertEquals("AA367404000520DD", it.identHex)
        }
        Fixtures.f8hpFactory().let {
            assertEquals(RadioModel.BF_F8HP, it.model)
            assertTrue(it.detection.recognized)
            assertEquals("N5R340BF8HP-1", it.firmwareVersion)
        }
    }

    @Test fun `decodes known channels from the baoclone Sunnyvale image`() {
        val img = Fixtures.uv5rSunnyvale()
        assertEquals(Channel(446_000_000, name = "S 446.0", power = Power.HIGH), img.channel(1))
        assertEquals(146_520_000, img.channel(4)!!.rxFreqHz)
        assertEquals("S146.52", img.channel(4)!!.name)
        assertTrue(img.usedChannelCount > 10)
    }

    @Test fun `F8HP factory channels decode with tri power levels`() {
        val ch = Fixtures.f8hpFactory().channel(1)!!
        assertEquals(140_125_000, ch.rxFreqHz)
        assertEquals(Power.HIGH, ch.power)
    }

    @Test fun `rewriting every fixture with its own channels is byte identical`() {
        for ((name, load) in Fixtures.all) {
            val img = load()
            val rewritten = img.withChannels(img.channels())
            assertArrayEquals("fixture $name", img.bytes(), rewritten.bytes())
        }
    }

    @Test fun `editing channels changes only the channel and name regions`() {
        for ((name, load) in Fixtures.all) {
            val img = load()
            val edited = img.withChannels(
                List(RadioImage.CHANNEL_COUNT) { i -> if (i % 3 == 0) Channel(146_000_000L + i * 15_000L, name = "CH$i") else null }
            )
            assertTrue("fixture $name", ImageRegions.differsOnlyInWritableRegions(img, edited))
            assertEquals(43, edited.usedChannelCount)
            assertEquals("CH3", edited.channel(3)!!.name)
        }
    }

    @Test fun `channel decode then encode round trips every fixture channel`() {
        for ((_, load) in Fixtures.all) {
            val img = load()
            val codec = ChannelCodec(img.model)
            img.channels().filterNotNull().forEach { ch ->
                val rec = codec.encodeRecord(ch)
                assertEquals(codec.normalize(ch), codec.decode(rec, 0, codec.encodeName(ch.name), 0))
            }
        }
    }

    @Test(expected = InvalidImageException::class)
    fun `rejects images of the wrong size`() {
        RadioImage.of(ByteArray(1000))
    }
}
