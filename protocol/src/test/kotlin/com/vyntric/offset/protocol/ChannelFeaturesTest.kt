package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modes, extra power levels and radio declared channel options, through the codecs, validator and CSV. */
class ChannelFeaturesTest {

    // nicFW950

    @Test fun `nicFW groups pack like the RMS app, first letter highest`() {
        assertEquals(0x0103L, NicFw950Codec.encodeGroups("AC"))
        assertEquals(0x01020304L, NicFw950Codec.encodeGroups("ABCD"))
        assertEquals("AC", NicFw950Codec.decodeGroups(0x0103L))
        assertEquals("AC", NicFw950Codec.decodeGroups(0x0301L))
        assertEquals("", NicFw950Codec.decodeGroups(0x00FF1B00L))
    }

    @Test fun `every nicFW option and mode is written where the RMS app reads it`() {
        val ch = Channel(
            14_074_000, Duplex.OFF, mode = Modulation.USB, name = "FT8 20m",
            options = mapOf(
                "nicfw.bandwidth" to "5", "nicfw.groups" to "BZ", "nicfw.reversed" to "true",
                "nicfw.clarifier" to "-1.25", "nicfw.scrambler" to "7",
            ),
        )
        val r = NicFw950Codec.encode(ch)
        assertEquals(7, r[18].toInt())
        assertEquals(5, r[19].toInt())
        assertEquals(26, r[8].toInt())
        assertEquals(2, r[9].toInt())
        assertEquals(1, r[22].toInt())
        assertEquals(-125, NicFw950Codec.u16(r, 16).toShort().toInt())
        assertEquals(7, r[26].toInt())
        assertEquals(0, r[21].toInt())
        assertEquals(NicFw950Codec.normalize(ch), NicFw950Codec.decode(r))
    }

    @Test fun `all six nicFW power levels round trip`() {
        for ((i, p) in listOf(Power.ZERO, Power.VERY_LOW, Power.LOW, Power.MID, Power.HIGH, Power.VERY_HIGH).withIndex()) {
            val r = NicFw950Codec.encode(Channel(146_000_000, power = p))
            assertEquals(i + 1, r[21].toInt())
            assertEquals(p, NicFw950Codec.decode(r)!!.power)
        }
    }

    @Test fun `a nicFW channel without options keeps the slot's settings`() {
        val existing = NicFw950Codec.encode(
            Channel(146_000_000, options = mapOf("nicfw.groups" to "A", "nicfw.scrambler" to "3", "nicfw.bandwidth" to "2")),
        )
        // As a channel from a CHIRP file arrives: same frequency, a new name, no options.
        val out = NicFw950Codec.encode(Channel(146_000_000, name = "From file"), existing)
        val back = NicFw950Codec.decode(out)!!
        assertEquals("A", back.options["nicfw.groups"])
        assertEquals("3", back.options["nicfw.scrambler"])
        assertEquals("auto bandwidth kept", "2", back.options["nicfw.bandwidth"])
        assertEquals("From file", back.name)
    }

    @Test fun `the bandwidth option wins over wide, and narrow from a file still writes narrow`() {
        val fromFile = NicFw950Codec.encode(Channel(146_000_000, wide = false))
        assertEquals(1, fromFile[19].toInt())
        val auto = NicFw950Codec.encode(Channel(146_000_000, wide = false, options = mapOf("nicfw.bandwidth" to "2")))
        assertEquals(2, auto[19].toInt())
    }

    @Test fun `a nicFW modulation byte past the list reads as auto and survives`() {
        val r = NicFw950Codec.encode(Channel(146_000_000))
        r[18] = 42
        val ch = NicFw950Codec.decode(r)!!
        assertEquals(Modulation.AUTO, ch.mode)
        assertArrayEquals(r, NicFw950Codec.encode(ch, r))
    }

    // Stock RT-950 Pro

    @Test fun `stock RT-950 options sit in the bits channel py uses`() {
        val ch = Channel(
            446_000_000, mode = Modulation.AM, name = "TEST",
            options = mapOf(
                "rt950.signalling" to "9", "rt950.scrambler" to "5", "rt950.encryption" to "2",
                "rt950.fhssLearn" to "true", "rt950.fhssCode" to "12AB9F",
            ),
        )
        val r = Rt950ProCodec.encode(ch)
        assertEquals(9, r[12].toInt() and 0x0F)
        assertEquals(5, (r[14].toInt() ushr 4) and 0x0F)
        val flags = r[15].toInt() and 0xFF
        assertEquals(2, (flags ushr 4) and 0x03)
        assertEquals(0x80, flags and 0x80)
        assertEquals(1, flags and 0x01)
        // _encode_fhss_code: last digit pair first, then A0.
        assertArrayEquals(byteArrayOf(0x9F.toByte(), 0xAB.toByte(), 0x12, 0xA0.toByte()), r.copyOfRange(16, 20))
        assertEquals(Rt950ProCodec.normalize(ch), Rt950ProCodec.decode(r))
    }

    @Test fun `clearing the hopping code writes FF as channel py does`() {
        val withCode = Rt950ProCodec.encode(Channel(446_000_000, options = mapOf("rt950.fhssCode" to "000001")))
        val cleared = Rt950ProCodec.encode(Channel(446_000_000, options = mapOf("rt950.fhssCode" to "")), withCode)
        assertArrayEquals(ByteArray(4) { 0xFF.toByte() }, cleared.copyOfRange(16, 20))
    }

    @Test fun `a stock channel without options keeps scrambler and encryption bits`() {
        val existing = Rt950ProCodec.encode(Channel(446_000_000, options = mapOf("rt950.scrambler" to "12", "rt950.encryption" to "3")))
        val out = Rt950ProCodec.encode(Channel(446_000_000, name = "NEW"), existing)
        assertEquals(12, (out[14].toInt() ushr 4) and 0x0F)
        assertEquals(3, (out[15].toInt() ushr 4) and 0x03)
    }

    @Test fun `a scrambler value the radio's menu never offers is kept and not flagged`() {
        val existing = Rt950ProCodec.encode(Channel(446_000_000, options = mapOf("rt950.scrambler" to "12")))
        val ch = Rt950ProCodec.decode(existing)!!
        assertEquals("12", ch.options["rt950.scrambler"])
        assertEquals(emptyList<ChannelValidator.Problem>(), ChannelValidator.validate(ch, Rt950ProProfile))
        assertEquals("Setting 12", Rt950ProCodec.SCRAMBLER.display("12"))
    }

    // Validator

    @Test fun `receive only modes need transmit off`() {
        val p = ChannelValidator.validate(Channel(7_074_000, mode = Modulation.USB), NicFw950Profile)
        assertEquals(listOf(ChannelValidator.Field.MODE), p.map { it.field })
        assertTrue(ChannelValidator.validate(Channel(7_074_000, Duplex.OFF, mode = Modulation.USB), NicFw950Profile).isEmpty())
    }

    @Test fun `a mode the radio lacks is flagged`() {
        val p = ChannelValidator.validate(Channel(118_000_000, Duplex.OFF, mode = Modulation.AM), RadioModel.UV5R)
        assertTrue(p.any { it.field == ChannelValidator.Field.MODE })
        assertTrue(ChannelValidator.validate(Channel(127_550_000, Duplex.OFF, mode = Modulation.AM), Rt950ProProfile).isEmpty())
    }

    @Test fun `bad option values name the option`() {
        val p = ChannelValidator.validate(
            Channel(146_000_000, options = mapOf("nicfw.groups" to "ABCDE", "nicfw.clarifier" to "999")),
            NicFw950Profile,
        )
        assertEquals(setOf("nicfw.groups", "nicfw.clarifier"), p.mapNotNull { it.optionKey }.toSet())
    }

    @Test fun `options for another radio are ignored, not flagged`() {
        assertTrue(ChannelValidator.validate(Channel(146_000_000, options = mapOf("nicfw.groups" to "nonsense")), Rt950ProProfile).isEmpty())
    }

    @Test fun `nicFW takes 10 Hz steps, others keep the channel raster`() {
        assertTrue(ChannelValidator.validate(Channel(7_074_010, Duplex.OFF, mode = Modulation.USB), NicFw950Profile).isEmpty())
        assertTrue(ChannelValidator.validate(Channel(146_520_010), Rt950ProProfile).any { it.field == ChannelValidator.Field.FREQUENCY })
    }

    @Test fun `PTT-ID codes are per radio`() {
        assertTrue(ChannelValidator.validate(Channel(146_520_000, pttIdCode = 14), RadioModel.UV5R).isEmpty())
        assertTrue(ChannelValidator.validate(Channel(146_520_000, pttIdCode = 20), NicFw950Profile).isEmpty())
    }

    // Power

    @Test fun `levels a radio lacks map to the nearest, the lower on a tie`() {
        assertEquals(Power.HIGH, RadioModel.UV5R.nearestPower(Power.VERY_HIGH))
        assertEquals(Power.LOW, RadioModel.UV5R.nearestPower(Power.MID))
        assertEquals(Power.LOW, RadioModel.UV5R.nearestPower(Power.ZERO))
        assertEquals(Power.LOW, Rt950ProProfile.nearestPower(Power.VERY_LOW))
        assertEquals(Power.HIGH, ChannelCodec(RadioModel.UV5R).normalize(Channel(146_520_000, power = Power.VERY_HIGH)).power)
    }

    // CHIRP CSV

    @Test fun `modes travel through CHIRP CSV`() {
        val channels = listOf(
            1 to Channel(127_550_000, Duplex.OFF, mode = Modulation.AM, name = "AIR"),
            2 to Channel(7_074_000, Duplex.OFF, mode = Modulation.USB, name = "FT8"),
            3 to Channel(446_000_000, wide = false, name = "NFM"),
            4 to Channel(121_500_000, Duplex.OFF, mode = Modulation.DSB, name = "DSB"),
        )
        val csv = ChirpCsv.write(channels, NicFw950Profile)
        val back = ChirpCsv.parse(csv, NicFw950Profile).rows.map { it.channel }
        assertEquals(Modulation.AM, back[0].mode)
        assertEquals(Modulation.USB, back[1].mode)
        assertEquals(Modulation.FM, back[2].mode)
        assertEquals(false, back[2].wide)
        assertEquals("CHIRP has no DSB; it travels as AM", Modulation.AM, back[3].mode)
    }

    @Test fun `an SSB memory from CHIRP arrives receive only`() {
        val csv = "Location,Name,Frequency,Duplex,Offset,Mode\n1,FT8,14.074000,,0.000000,USB\n"
        val ch = ChirpCsv.parse(csv, NicFw950Profile).rows.single().channel
        assertEquals(Duplex.OFF, ch.duplex)
        assertTrue(ChannelValidator.validate(ch, NicFw950Profile).isEmpty())
    }

    @Test fun `nicFW never writes transmit for a receive only mode`() {
        val r = NicFw950Codec.encode(Channel(14_074_000, Duplex.SIMPLEX, mode = Modulation.USB))
        assertEquals(0, r[21].toInt())
        assertEquals(Duplex.OFF, NicFw950Codec.decode(r)!!.duplex)
    }

    @Test fun `digital CHIRP modes are still refused`() {
        val csv = "Location,Name,Frequency,Mode\n1,X,146.520000,DV\n"
        val r = ChirpCsv.parse(csv, NicFw950Profile)
        assertTrue(r.rows.isEmpty())
        assertTrue(r.errors.single().contains("DV"))
    }

    @Test fun `a nicFW sample channel's options are all valid`() {
        val sample = NicFw950Driver.sampleImage()
        sample.channels().filterNotNull().forEach {
            assertEquals(it.toString(), emptyList<ChannelValidator.Problem>(), ChannelValidator.validate(it, NicFw950Profile))
        }
        assertNull(sample.channel(7))
    }
}
