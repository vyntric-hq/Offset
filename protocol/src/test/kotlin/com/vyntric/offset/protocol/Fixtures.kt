package com.vyntric.offset.protocol

object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    /** CHIRP's own test image (UV-5R, firmware BFB231, with CHIRP metadata trailer). */
    fun chirpUv5r(): Uv5rImage = ChirpImg.decode(bytes("chirp-uv5r-bfb231.img")).image as Uv5rImage
    fun uv5rFactory(): Uv5rImage = Uv5rImage.of(bytes("baoclone-uv5r-factory.img"))
    fun f8hpFactory(): Uv5rImage = Uv5rImage.of(bytes("baoclone-bff8hp-factory.img"))
    fun uv5rSunnyvale(): Uv5rImage = Uv5rImage.of(bytes("baoclone-uv5r-sunnyvale.img"))

    val all: Map<String, () -> Uv5rImage> = mapOf(
        "chirp-uv5r" to ::chirpUv5r,
        "uv5r-factory" to ::uv5rFactory,
        "f8hp-factory" to ::f8hpFactory,
        "uv5r-sunnyvale" to ::uv5rSunnyvale,
    )
}
