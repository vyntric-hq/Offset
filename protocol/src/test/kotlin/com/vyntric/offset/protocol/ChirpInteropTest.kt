package com.vyntric.offset.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Interop with desktop CHIRP. The fixture was produced by exporting an image from Offset, loading
 * it in CHIRP (uv5r.py, headless) and saving it again with CHIRP.
 */
class ChirpInteropTest {
    @Test fun `reads an image that desktop CHIRP saved from an Offset export`() {
        val parsed = ChirpImg.decode(Fixtures.bytes("chirp-resaved-offset-export.img"))
        assertEquals("UV-5R", parsed.metadata["model"])
        val ch = parsed.image.channels()
        assertEquals(Channel(146_115_000, Duplex.PLUS, 600_000, Squelch.Ctcss(1000), name = "AA6BT"), ch[2])
        assertEquals(Channel(441_800_000, Duplex.PLUS, 5_000_000, Squelch.Ctcss(1000), name = "AD6RC"), ch[3])
        // Re-exporting from Offset gives CHIRP back the same image bytes.
        assertEquals(parsed.image, ChirpImg.decode(ChirpImg.encode(parsed.image)).image)
    }

    /** Writes a CSV for the CHIRP interop check described in docs/PROTOCOL.md. */
    @Test fun `writes a CSV sample for the CHIRP interop check`() {
        val img = ChirpImg.decode(Fixtures.bytes("chirp-resaved-offset-export.img")).image
        val extra = listOf(
            10 to Channel(444_000_000, Duplex.PLUS, 5_000_000, Squelch.Dcs(23, true), Squelch.Dcs(23)),
            11 to Channel(146_000_000, Duplex.SPLIT, 446_000_000, Squelch.Dcs(754), Squelch.Ctcss(885), wide = false, scanAdd = false),
            12 to Channel(162_550_000, Duplex.OFF, name = "WX1", power = Power.LOW),
        )
        val rows = img.channels().withIndex().mapNotNull { (i, c) -> c?.let { i to it } } + extra
        val out = File("build/chirp-interop/offset.csv").apply { parentFile.mkdirs() }
        out.writeText(ChirpCsv.write(rows, RadioModel.UV5R))
        assertEquals(rows.size, ChirpCsv.parse(out.readText()).rows.size)
    }
}
