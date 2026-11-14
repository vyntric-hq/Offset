package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Expected values were computed with Nathan Barguss's rt950pro/transport.py (MIT) at commit
 * 254d2cd: `_build_encryption_frame` with `random.Random(seed)` for seeds 0..7, and `_apply_xor`.
 */
class Rt950ProCipherTest {
    private val pythonFrames = listOf(
        "53454E44230108100F0C090F0B120610040904031308111300" to "K DH",
        "53454E44140208030F0E0F0C06030F000C0D13000E08071200" to " EIY",
        "53454E4410020B0509081306130112050D0C100B110E100800" to "A ES",
        "53454E441411040B130F120213000F081107060F11110F0C00" to "BHT ",
        "53454E4412030C0F040202000C1109010710110B0805030800" to "A ES",
        "53454E442210000E070105030B0F070C1103120700060D0800" to "XN Y",
        "53454E441308010004120F0B0A00080F060D11110306121100" to "K DH",
        "53454E44210C010211030B1201100601020D0D020702110D00" to "J PK",
    )

    @Test fun `key derivation matches the Python transport for its own frames`() {
        for ((frame, key) in pythonFrames) {
            assertEquals(frame, key, Rt950ProCipher.keyFor(hex(frame))?.toString(Charsets.US_ASCII))
        }
    }

    @Test fun `frames built from any seed are well formed and pick a table key`() {
        repeat(500) { seed ->
            val frame = Rt950ProCipher.newFrame(Random(seed))
            assertEquals(Rt950ProCipher.FRAME_SIZE, frame.size)
            assertEquals("SEND", frame.copyOf(4).toString(Charsets.US_ASCII))
            val selector = frame[4].toInt()
            assertTrue("selector $selector", selector in 0x10..0x14 || selector in 0x20..0x24)
            assertTrue((5..23).all { frame[it] in 0..19 })
            assertEquals(0, frame[24].toInt())
            val key = Rt950ProCipher.keyFor(frame)
            assertTrue(Rt950ProCipher.KEYS.any { it.contentEquals(key) })
        }
    }

    @Test fun `malformed frames select no key`() {
        val good = hex(pythonFrames[0].first)
        assertNull(Rt950ProCipher.keyFor(good.copyOf(24)))
        assertNull(Rt950ProCipher.keyFor(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(Rt950ProCipher.keyFor(good.copyOf().also { it[4] = 0x30 }))
        assertNull(Rt950ProCipher.keyFor(good.copyOf().also { it[4] = 0x10; it[5] = 20 }))
    }

    @Test fun `xor follows the skip rules exactly as the Python transport`() {
        val data = ByteArray(256) { it.toByte() }
        val all = Rt950ProCipher.KEYS.flatMap { key ->
            listOf(data.copyOfRange(0, 128), data.copyOfRange(128, 256)).map { Rt950ProCipher.apply(it, key) }
        }.reduce { a, b -> a + b }
        assertEquals(
            "88f16b7e228739b399dde011149bedbf277fd9ab9086e3ba50e60392d02af949",
            MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it) },
        )
        assertArrayEquals(
            hex("00495603464D52074A415E0B4E455A0F52594613565D42175A514E1B5E554A1F"),
            Rt950ProCipher.apply(data.copyOf(32), "BHT ".toByteArray()),
        )
        assertArrayEquals(
            hex("09410E190D450A1D01490611054D021519511E091D551A0D11591601155D1205"),
            Rt950ProCipher.apply(data.copyOfRange(0x40, 0x60), "I LZ".toByteArray()),
        )
    }

    @Test fun `xor is its own inverse for every key and byte`() {
        val data = ByteArray(256) { it.toByte() }
        for (key in Rt950ProCipher.KEYS) {
            assertArrayEquals(data, Rt950ProCipher.apply(Rt950ProCipher.apply(data, key), key))
        }
    }

    @Test fun `bytes under a space key byte and sentinel bytes pass through`() {
        val key = "BHT ".toByteArray()
        val plain = byteArrayOf(0x00, 0xFF.toByte(), 'T'.code.toByte(), 0x41)
        // 00 and FF are kept, 'T' equals its key byte, and key byte 3 is a space.
        assertArrayEquals(plain, Rt950ProCipher.apply(plain, key))
        val notKey = ('T'.code xor 0xFF).toByte()
        assertEquals(notKey, Rt950ProCipher.apply(byteArrayOf(0, 0, notKey), key)[2])
    }

    companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
