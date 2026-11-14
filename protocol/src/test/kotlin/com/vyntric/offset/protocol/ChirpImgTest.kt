package com.vyntric.offset.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ChirpImgTest {
    @Test fun `parses CHIRP's own test image including its metadata`() {
        val parsed = ChirpImg.decode(Fixtures.bytes("chirp-uv5r-bfb231.img"))
        assertEquals(Uv5rImage.FULL_SIZE, parsed.image.size)
        assertEquals("UV-5R", parsed.metadata["model"])
        assertEquals("Baofeng", parsed.metadata["vendor"])
        assertEquals(Uv5rDriver, parsed.image.driver)
        assertEquals(RadioModel.UV5R, (parsed.image as Uv5rImage).model)
    }

    @Test fun `export writes image, CHIRP magic and base64 JSON trailer`() {
        val img = Fixtures.f8hpFactory()
        val file = ChirpImg.encode(img)
        assertArrayEquals(img.bytes(), file.copyOfRange(0, Uv5rImage.FULL_SIZE))
        val magic = file.copyOfRange(Uv5rImage.FULL_SIZE, Uv5rImage.FULL_SIZE + ChirpImg.MAGIC.size)
        assertArrayEquals(byteArrayOf(0, -1, 'c'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
            'p'.code.toByte(), 0xEE.toByte(), 'i'.code.toByte(), 'm'.code.toByte(), 'g'.code.toByte(), 0, 1), magic)
        val json = String(Base64.getDecoder().decode(file.copyOfRange(Uv5rImage.FULL_SIZE + magic.size, file.size)))
        assertEquals(
            "{\"mem_extra\": {}, \"rclass\": \"BaofengBFF8HPRadio\", \"vendor\": \"Baofeng\", \"model\": \"BF-F8HP\", \"variant\": \"\"}",
            json,
        )
    }

    @Test fun `export then import round trips every fixture`() {
        for ((name, load) in Fixtures.all) {
            val img = load()
            val back = ChirpImg.decode(ChirpImg.encode(img))
            assertEquals("fixture $name", img, back.image)
            assertEquals(img.model.chirpModel, back.metadata["model"])
        }
    }

    @Test fun `imports a bare image without trailer`() {
        val raw = Fixtures.bytes("baoclone-uv5r-factory.img")
        val parsed = ChirpImg.decode(raw)
        assertTrue(parsed.metadata.isEmpty())
        assertArrayEquals(raw, parsed.image.bytes())
    }

    @Test(expected = InvalidImageException::class)
    fun `rejects an img for another radio`() {
        val json = "{\"rclass\": \"KenwoodTHD74\", \"vendor\": \"Kenwood\", \"model\": \"TH-D74\"}"
        ChirpImg.decode(Fixtures.uv5rFactory().bytes() + ChirpImg.MAGIC + Base64.getEncoder().encode(json.toByteArray()))
    }
}
