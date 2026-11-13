package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpCsvTest {
    @Test fun `export then import round trips channels`() {
        val channels = listOf(
            0 to Channel(146_520_000, name = "CALL"),
            1 to Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW"),
            2 to Channel(442_100_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(1318), Squelch.Ctcss(1318), wide = false),
            3 to Channel(444_000_000, Duplex.PLUS, 5_000_000, Squelch.Dcs(23, true), Squelch.Dcs(23), power = Power.LOW),
            4 to Channel(146_000_000, Duplex.SPLIT, 446_000_000, Squelch.Dcs(754), Squelch.Ctcss(885), scanAdd = false),
            5 to Channel(162_550_000, Duplex.OFF, name = "WX, \"1\""),
        )
        val csv = ChirpCsv.write(channels, RadioModel.UV5R)
        val parsed = ChirpCsv.parse(csv)
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals(channels.map { it.second }, parsed.rows.map { it.channel })
        assertEquals(channels.map { it.first }, parsed.rows.map { it.location })
    }

    @Test fun `export uses CHIRP's header and number formats`() {
        val csv = ChirpCsv.write(listOf(0 to Channel(146_940_000, Duplex.MINUS, 600_000, Squelch.Ctcss(1000), name = "W1AW")), RadioModel.UV5R)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(ChirpCsv.HEADER.joinToString(","), lines[0])
        assertEquals("0,W1AW,146.940000,-,0.600000,Tone,100.0,88.5,023,NN,023,Tone->Tone,FM,5.00,,4.0W,,,,,", lines[1])
    }

    @Test fun `imports a CHIRP desktop export with extra coordinator columns`() {
        val csv = """
            Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment,URCALL,RPT1CALL,RPT2CALL,Callsign,Latitude,Longitude,State
            1,K6ABC,145.230000,-,0.600000,TSQL,88.5,100.0,023,NN,FM,5.00,,"San Jose, CA",,,,K6ABC,37.33,-121.89,CA
            2,,147.000000,+,0.600000,,88.5,88.5,023,NN,NFM,5.00,S,,,,,,,,
        """.trimIndent()
        val parsed = ChirpCsv.parse(csv)
        assertTrue(parsed.errors.isEmpty())
        val first = parsed.rows[0]
        assertEquals(Squelch.Ctcss(1000), first.channel.txSquelch)
        assertEquals(Squelch.Ctcss(1000), first.channel.rxSquelch)
        assertEquals("San Jose, CA", first.comment)
        assertEquals("37.33", first.extras["Latitude"])
        assertEquals(false, parsed.rows[1].channel.wide)
        assertEquals(false, parsed.rows[1].channel.scanAdd)
    }

    @Test fun `power accepts CHIRP watts and labels`() {
        val rows = listOf("8.0W", "4.0W", "1.0W", "High", "Low")
        val csv = "Location,Frequency,Power\n" + rows.mapIndexed { i, p -> "$i,146.520000,$p" }.joinToString("\n")
        val tri = ChirpCsv.parse(csv, RadioModel.BF_F8HP).rows.map { it.channel.power }
        assertEquals(listOf(Power.HIGH, Power.MID, Power.LOW, Power.HIGH, Power.LOW), tri)
        val dual = ChirpCsv.parse(csv, RadioModel.UV5R).rows.map { it.channel.power }
        assertEquals(listOf(Power.HIGH, Power.HIGH, Power.LOW, Power.HIGH, Power.LOW), dual)
    }

    @Test fun `reports bad lines without failing the whole file`() {
        val parsed = ChirpCsv.parse("Location,Frequency,Tone\n1,146.52,Bogus\n2,147.00,\n")
        assertEquals(1, parsed.rows.size)
        assertEquals(1, parsed.errors.size)
    }

    @Test fun `non CSV input is rejected with a message`() {
        assertTrue(ChirpCsv.parse("hello\nworld").errors.isNotEmpty())
        assertTrue(ChirpCsv.parse("").errors.isNotEmpty())
    }
}
