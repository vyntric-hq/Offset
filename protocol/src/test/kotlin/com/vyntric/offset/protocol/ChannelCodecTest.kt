package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCodecTest {
    private val uv5r = ChannelCodec(RadioModel.UV5R)
    private val f8hp = ChannelCodec(RadioModel.BF_F8HP)
    private val emptyName = ByteArray(16) { 0xFF.toByte() }

    private fun roundTrip(codec: ChannelCodec, ch: Channel): Channel? {
        val rec = codec.encodeRecord(ch)
        val name = codec.encodeName(ch.name)
        return codec.decode(rec, 0, name, 0)
    }

    private val samples = listOf(
        Channel(146_520_000, name = "CALL"),
        Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW"),
        Channel(147_120_000, Duplex.PLUS, 600_000, Squelch.Ctcss(885), Squelch.Ctcss(885)),
        Channel(442_100_000, Duplex.PLUS, 5_000_000, Squelch.Dcs(23), Squelch.Dcs(23), wide = false),
        Channel(444_000_000, Duplex.PLUS, 5_000_000, Squelch.Dcs(754, inverted = true), Squelch.Dcs(25)),
        Channel(146_000_000, Duplex.SPLIT, 446_000_000, Squelch.Ctcss(1230), Squelch.Dcs(411)),
        Channel(162_550_000, Duplex.OFF, name = "WX1"),
        Channel(446_000_000, power = Power.LOW, scanAdd = false, busyLockout = true, pttId = PttId.BOTH, pttIdCode = 14, name = "S 446.0"),
        Channel(145_500_000, txSquelch = Squelch.None, rxSquelch = Squelch.Ctcss(2541), pttId = PttId.EOT, name = "!@#$%^&"),
    )

    @Test fun `round trips representative channels on a dual power radio`() {
        samples.forEach { assertEquals(it, roundTrip(uv5r, it)) }
    }

    @Test fun `round trips all three power levels on a tri power radio`() {
        for (p in listOf(Power.HIGH, Power.MID, Power.LOW)) {
            val ch = Channel(146_520_000, power = p)
            assertEquals(ch, roundTrip(f8hp, ch))
        }
    }

    @Test fun `mid power on a dual power radio is stored as low`() {
        assertEquals(Power.LOW, roundTrip(uv5r, Channel(146_520_000, power = Power.MID))!!.power)
    }

    @Test fun `every CTCSS tone and DCS code survives encode and decode`() {
        for (t in Tones.CTCSS_TENTHS) {
            assertEquals(Squelch.Ctcss(t), ChannelCodec.decodeSquelch(ChannelCodec.encodeSquelch(Squelch.Ctcss(t))))
        }
        for (c in Tones.DCS_CODES) for (inv in listOf(false, true)) {
            assertEquals(Squelch.Dcs(c, inv), ChannelCodec.decodeSquelch(ChannelCodec.encodeSquelch(Squelch.Dcs(c, inv))))
        }
    }

    @Test fun `tone encodings match CHIRP`() {
        assertEquals(885, ChannelCodec.encodeSquelch(Squelch.Ctcss(885)))
        assertEquals(1, ChannelCodec.encodeSquelch(Squelch.Dcs(23)))
        assertEquals(0x6A, ChannelCodec.encodeSquelch(Squelch.Dcs(23, inverted = true)))
        assertEquals(Tones.DCS_CODES.size, ChannelCodec.encodeSquelch(Squelch.Dcs(754)))
        assertEquals(Squelch.None, ChannelCodec.decodeSquelch(0xFFFF))
        assertEquals(Squelch.None, ChannelCodec.decodeSquelch(0))
    }

    @Test fun `record bytes match the documented layout`() {
        val ch = Channel(
            146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), Squelch.Dcs(23, true),
            power = Power.LOW, wide = true, scanAdd = true, busyLockout = true, pttId = PttId.BOT, pttIdCode = 3,
        )
        val rec = uv5r.encodeRecord(ch)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x40, 0x69, 0x14, // rx 146.94000
                0x00, 0x40, 0x63, 0x14, // tx 146.34000
                0x6A, 0x00, // rxtone D023I
                0xE8.toByte(), 0x03, // txtone 100.0
                0x03, 0x00, 0x01, // scode 3, lowpower 1
                (0x40 or 0x08 or 0x04 or 0x01).toByte(),
            ),
            rec,
        )
    }

    @Test fun `TX inhibit decodes from both FF and 00 transmit frequency`() {
        val rec = uv5r.encodeRecord(Channel(146_520_000))
        for (fill in listOf(0xFF.toByte(), 0x00.toByte())) {
            for (i in 4..7) rec[i] = fill
            assertEquals(Duplex.OFF, uv5r.decode(rec, 0, emptyName, 0)!!.duplex)
        }
    }

    @Test fun `empty slot decodes to null`() {
        assertNull(uv5r.decode(ByteArray(16) { 0xFF.toByte() }, 0, emptyName, 0))
    }

    @Test fun `names are uppercased, filtered, truncated and FF padded`() {
        val name = uv5r.encodeName("w1aw_repeater")
        assertEquals("W1AW RE", String(name.copyOfRange(0, 7), Charsets.US_ASCII))
        val short = uv5r.encodeName("ab")
        assertTrue(short.copyOfRange(2, 16).all { it == 0xFF.toByte() })
    }

    @Test fun `name encoding preserves the trailing unknown bytes`() {
        val existing = ByteArray(16) { it.toByte() }
        val out = uv5r.encodeName("X", existing)
        assertArrayEquals(existing.copyOfRange(7, 16), out.copyOfRange(7, 16))
    }

    @Test fun `unchanged channel keeps undocumented bits, edited channel clears them like CHIRP`() {
        val base = uv5r.encodeRecord(Channel(470_000_000))
        base[12] = (base[12].toInt() or 0x10).toByte() // isuhf, as found in factory images
        val name = uv5r.encodeName("")
        val same = uv5r.decode(base, 0, name, 0)!!
        assertArrayEquals(base, uv5r.encodeRecord(same, base, name))
        val edited = uv5r.encodeRecord(same.copy(rxFreqHz = 146_520_000), base, name)
        assertEquals(0, edited[12].toInt() and 0x10)
    }
}
