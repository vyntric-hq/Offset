package com.vyntric.offset.protocol

import com.vyntric.offset.protocol.Rt950ProCipherTest.Companion.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Records and expected values were produced with Nathan Barguss's rt950pro/channel.py (MIT) at
 * commit 254d2cd: `ChannelRecord.from_bytes` for the decodes, attribute edits then `to_bytes`
 * for the encodes.
 */
class Rt950ProCodecTest {
    private val codec = Rt950ProCodec

    // CALLING: simplex, no tones, wide, scan, TX on.
    private val r1 = "00206514002065140000000000000006FFFFFFFF43414C4C494E47FFFFFFFFFF"
    // RPT 2M: CTCSS 100.0 TX, PTT both, medium, scrambler 5, narrow, BCL, FHSS 12AB34.
    private val r2 = "00296514006946140000E8030003514E34AB12A052505420324DFFFFFFFFFFFF"
    // GB2312 name, DCS 023N RX / 754I TX, low, encryption 3, signalling group 7.
    private val r3 = "00104244001047440100D20007000236FFFFFFFFD6D0BCCCFFFFFFFFFFFFFFFF"
    // AM airband, receive only, FF FF tones.
    private val r4 = "00507512FFFFFFFFFFFFFFFF00000005FFFFFFFF414952FFFFFFFFFFFFFFFFFF"
    // TX disabled with a TX frequency stored, learn FHSS, PTT EOT.
    private val r5 = "00552516005525160000000000020084FFFFFFFF5758FFFFFFFFFFFFFFFFFFFF"
    // 12 character name, CTCSS 254.1 both ways, not in scan.
    private val r7 = "2506004425060044ED09ED0900000102FFFFFFFF4142434445464748494A4B4C"

    @Test fun `decode agrees with channel py`() {
        assertEquals(Channel(146_520_000, name = "CALLING"), codec.decode(hex(r1)))
        assertEquals(
            Channel(
                146_529_000, Duplex.MINUS, 1_860_000, txSquelch = Squelch.Ctcss(1000), power = Power.MID,
                wide = false, busyLockout = true, pttId = PttId.BOTH, name = "RPT 2M",
            ),
            codec.decode(hex(r2)),
        )
        assertEquals(
            Channel(
                444_210_000, Duplex.PLUS, 500_000, Squelch.Dcs(754, inverted = true), Squelch.Dcs(23),
                power = Power.LOW, name = "中继",
            ),
            codec.decode(hex(r3)),
        )
        assertEquals(Channel(127_550_000, Duplex.OFF, name = "AIR"), codec.decode(hex(r4)))
        assertEquals(Channel(162_555_000, Duplex.OFF, pttId = PttId.EOT, name = "WX"), codec.decode(hex(r5)))
        assertEquals(
            Channel(
                440_006_250, txSquelch = Squelch.Ctcss(2541), rxSquelch = Squelch.Ctcss(2541), power = Power.MID,
                scanAdd = false, name = "ABCDEFGHIJKL",
            ),
            codec.decode(hex(r7)),
        )
        assertNull(codec.decode(ByteArray(32) { 0xFF.toByte() }))
        assertNull(codec.decode(ByteArray(32)))
    }

    @Test fun `edits encode to the same bytes as channel py`() {
        fun edit(record: String, change: (Channel) -> Channel) =
            codec.encode(change(codec.decode(hex(record))!!), hex(record))

        assertArrayEquals(
            hex("00505214005046140000E8030003514E34AB12A05250542042FFFFFFFFFFFFFF"),
            edit(r2) { it.copy(rxFreqHz = 145_250_000, offsetHz = 600_000, name = "RPT B") },
        )
        assertArrayEquals(
            hex("00104244001047447503D20007000036FFFFFFFFD6D0BCCCFFFFFFFFFFFFFFFF"),
            edit(r3) { it.copy(power = Power.HIGH, rxSquelch = Squelch.Ctcss(885)) },
        )
        assertArrayEquals(
            hex("0020651400206514000000000001004AFFFFFFFF43414C4C494E47FFFFFFFFFF"),
            edit(r1) { it.copy(wide = false, scanAdd = false, busyLockout = true, pttId = PttId.BOT) },
        )
        assertArrayEquals(
            hex("00507512FFFFFFFFFFFFFFFF00000005FFFFFFFF41495242414E442032FFFFFF"),
            edit(r4) { it.copy(name = "AIRBAND 2") },
        )
        assertArrayEquals(
            hex("002965140069461400006A000003514E34AB12A052505420324DFFFFFFFFFFFF"),
            edit(r2) { it.copy(txSquelch = Squelch.Dcs(23, inverted = true)) },
        )
        assertArrayEquals(
            hex("00008543000009430000750300000206FFFFFFFF4E4557204F4E45FFFFFFFFFF"),
            codec.encode(
                Channel(438_500_000, Duplex.MINUS, 7_600_000, txSquelch = Squelch.Ctcss(885), power = Power.LOW, name = "NEW ONE"),
                ByteArray(32) { 0xFF.toByte() },
            ),
        )
    }

    @Test fun `unchanged channels keep every byte`() {
        for (r in listOf(r1, r2, r3, r4, r5, r7)) {
            assertArrayEquals(r, hex(r), codec.encode(codec.decode(hex(r))!!, hex(r)))
        }
    }

    @Test fun `am, fhss, scrambler, encryption and signalling survive an edit`() {
        val r2Edited = codec.encode(codec.decode(hex(r2))!!.copy(rxFreqHz = 145_500_000), hex(r2))
        assertEquals(0x51, r2Edited[14].toInt() and 0xFF) // scrambler 5 kept, power medium kept
        assertArrayEquals(hex("34AB12A0"), r2Edited.copyOfRange(16, 20))
        val r3Edited = codec.encode(codec.decode(hex(r3))!!.copy(power = Power.HIGH), hex(r3))
        assertEquals(0x07, r3Edited[12].toInt()) // signalling group
        assertEquals(0x36, r3Edited[15].toInt()) // encryption bits
        assertArrayEquals(hex("D6D0BCCC"), r3Edited.copyOfRange(20, 24)) // GB2312 name not edited
        val r4Edited = codec.encode(codec.decode(hex(r4))!!.copy(rxFreqHz = 121_500_000), hex(r4))
        assertEquals(0x05, r4Edited[15].toInt()) // AM, TX still disabled
        val r5Edited = codec.encode(codec.decode(hex(r5))!!.copy(name = "WX 2"), hex(r5))
        assertEquals(0x84, r5Edited[15].toInt() and 0xFF) // learn FHSS kept
        val odd = hex(r1).also { it[12] = 0xA3.toByte(); it[13] = 0xB9.toByte() }
        val oddEdited = codec.encode(codec.decode(odd)!!.copy(name = "X"), odd)
        assertEquals(0xA3, oddEdited[12].toInt() and 0xFF)
        assertEquals(0xB9, oddEdited[13].toInt() and 0xFF) // PTT ID nibble 9 is not modeled and stays
        val repowered = codec.encode(codec.decode(hex(r2))!!.copy(power = Power.HIGH), hex(r2))
        assertEquals(0x50, repowered[14].toInt()) // power rewritten, scrambler 5 kept
        val reptt = codec.encode(codec.decode(odd)!!.copy(pttId = PttId.EOT), odd)
        assertEquals(0xB2, reptt[13].toInt() and 0xFF) // PTT ID rewritten, high nibble kept
    }

    @Test fun `tx inhibit clears the TX enable bit and back`() {
        val off = codec.encode(Channel(146_520_000, Duplex.OFF), hex(r1))
        assertEquals(0x04, off[15].toInt())
        assertEquals(Duplex.OFF, codec.decode(off)!!.duplex)
        val on = codec.encode(codec.decode(hex(r5))!!.copy(duplex = Duplex.SIMPLEX), hex(r5))
        assertEquals(0x86, on[15].toInt() and 0xFF)
        assertEquals(Duplex.SIMPLEX, codec.decode(on)!!.duplex)
    }

    @Test fun `a new channel starts from the template`() {
        val ch = Channel(145_000_000, name = "Mixed Case 12345")
        val bytes = codec.encode(ch)
        assertEquals(codec.normalize(ch), codec.decode(bytes))
        assertEquals("Mixed Case 1", codec.decode(bytes)!!.name)
        assertArrayEquals(hex("00000000000006FFFFFFFF"), bytes.copyOfRange(9, 20))
    }

    @Test fun `split and long offsets round trip`() {
        for (ch in listOf(
            Channel(145_800_000, Duplex.SPLIT, 435_000_000),
            Channel(29_600_000, Duplex.MINUS, 100_000),
            Channel(446_006_250, Duplex.PLUS, 5_000_000, Squelch.Dcs(645, true), Squelch.Dcs(25)),
        )) assertEquals(codec.normalize(ch), codec.decode(codec.encode(ch)))
    }

    @Test fun `dcs table is channel py's 105 codes, normal then inverted`() {
        assertEquals(105, Rt950ProCodec.DCS_CODES.size)
        assertEquals(23, Rt950ProCodec.DCS_CODES.first())
        assertEquals(754, Rt950ProCodec.DCS_CODES.last())
        assertEquals(645, Rt950ProCodec.DCS_CODES[93])
        fun raw(lo: Int, hi: Int = 0) = codec.decodeSquelch(byteArrayOf(lo.toByte(), hi.toByte()), 0)
        assertEquals(Squelch.Dcs(23), raw(1))
        assertEquals(Squelch.Dcs(754), raw(105))
        assertEquals(Squelch.Dcs(23, true), raw(106))
        assertEquals(Squelch.Dcs(754, true), raw(210))
        assertEquals(Squelch.None, raw(211)) // unknown index reads as no tone and is preserved
        assertEquals(Squelch.None, raw(0xFF, 0xFF))
        assertEquals(Squelch.Ctcss(670), raw(0x9E, 0x02))
        for (code in Rt950ProCodec.DCS_CODES) for (inv in listOf(false, true)) {
            val out = ByteArray(2)
            codec.encodeSquelch(Squelch.Dcs(code, inv), out, 0)
            assertEquals(Squelch.Dcs(code, inv), codec.decodeSquelch(out, 0))
        }
        for (tone in Tones.CTCSS_TENTHS) {
            val out = ByteArray(2)
            codec.encodeSquelch(Squelch.Ctcss(tone), out, 0)
            assertEquals(Squelch.Ctcss(tone), codec.decodeSquelch(out, 0))
        }
    }
}
