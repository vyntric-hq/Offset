package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcdTest {
    @Test fun `146_52 MHz encodes as CHIRP lbcd`() {
        val out = ByteArray(4)
        Bcd.encodeLittleEndian(14_652_000, out, 0, 4)
        assertArrayEquals(byteArrayOf(0x00, 0x20, 0x65, 0x14), out)
    }

    @Test fun `round trips every 2_5 kHz step in both bands`() {
        val out = ByteArray(4)
        for (hz in (136_000_000L..174_000_000L step 2_500L) + (400_000_000L..520_000_000L step 6_250L)) {
            Bcd.encodeLittleEndian(hz / 10, out, 0, 4)
            assertEquals(hz / 10, Bcd.decodeLittleEndian(out, 0, 4))
        }
    }

    @Test fun `non decimal nibble decodes to null`() {
        assertNull(Bcd.decodeLittleEndian(byteArrayOf(0x0A, 0, 0, 0), 0, 4))
        assertNull(Bcd.decodeLittleEndian(byteArrayOf(-1, -1, -1, -1), 0, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `value too large for the field is rejected`() {
        Bcd.encodeLittleEndian(100_000_000, ByteArray(4), 0, 4)
    }
}
