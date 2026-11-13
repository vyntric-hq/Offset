package com.vyntric.offset.protocol

object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    /** CHIRP's own test image (UV-5R, firmware BFB231, with CHIRP metadata trailer). */
    fun chirpUv5r(): RadioImage = ChirpImg.decode(bytes("chirp-uv5r-bfb231.img")).image
    fun uv5rFactory(): RadioImage = RadioImage.of(bytes("baoclone-uv5r-factory.img"))
    fun f8hpFactory(): RadioImage = RadioImage.of(bytes("baoclone-bff8hp-factory.img"))
    fun uv5rSunnyvale(): RadioImage = RadioImage.of(bytes("baoclone-uv5r-sunnyvale.img"))

    val all: Map<String, () -> RadioImage> = mapOf(
        "chirp-uv5r" to ::chirpUv5r,
        "uv5r-factory" to ::uv5rFactory,
        "f8hp-factory" to ::f8hpFactory,
        "uv5r-sunnyvale" to ::uv5rSunnyvale,
    )
}
