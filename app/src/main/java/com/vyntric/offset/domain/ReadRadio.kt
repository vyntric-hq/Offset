/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.Sleeper
import com.vyntric.offset.protocol.Uv5rClone
import java.time.Clock
import java.time.LocalDate

/** Reads the full image from the radio; the first read of a radio is saved as a factory image. */
class ReadRadio(
    private val link: RadioLink,
    private val library: ImageLibrary,
    private val session: RadioSession,
    private val clock: Clock,
    private val sleeper: Sleeper,
) {
    data class Result(val image: RadioImage, val factoryImageId: Long?, val factoryImageName: String?)

    suspend operator fun invoke(onProgress: (CloneProgress) -> Unit): Result {
        val image = link.session { port -> Uv5rClone(port, sleeper, onProgress).download() }
        session.update(image, clock.millis())
        if (library.hasFactoryImage(image.identHex)) return Result(image, null, null)
        val name = factoryName(image)
        val id = library.save(image, name, ImageKind.FACTORY, notes = "Saved automatically the first time this radio was read.")
        return Result(image, id, name)
    }

    private fun factoryName(image: RadioImage): String {
        val fw = image.firmwareVersion?.removePrefix("Ver ")?.trim()?.takeIf { it.isNotEmpty() }
        val ident = listOfNotNull(image.model.displayName, fw).joinToString(" ")
        return "Factory read $ident ${LocalDate.now(clock)}"
    }
}
