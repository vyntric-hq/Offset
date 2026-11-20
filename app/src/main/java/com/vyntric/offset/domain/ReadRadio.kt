/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.Sleeper
import java.time.Clock

/**
 * Reads the full image from the radio; the first read of a radio is saved as a factory image.
 * [driver] is the radio type the user selected; the protocols differ, so it cannot be probed.
 */
class ReadRadio(
    private val link: RadioLink,
    private val library: ImageLibrary,
    private val session: RadioSession,
    private val clock: Clock,
    private val sleeper: Sleeper,
    private val driver: () -> RadioDriver,
) {
    data class Result(val image: RadioImage, val factoryImageId: Long?, val factoryImageName: String?)

    suspend operator fun invoke(onProgress: (CloneProgress) -> Unit): Result {
        val d = driver()
        val image = link.session(d) { port -> d.clone(port, sleeper, onProgress).download() }
        session.update(image, clock.millis())
        if (library.hasFactoryImage(image.identity)) return Result(image, null, null)
        val name = factoryName(image)
        val id = library.save(image, name, ImageKind.FACTORY, notes = "Saved automatically the first time this radio was read.")
        return Result(image, id, name)
    }

    private fun factoryName(image: RadioImage): String {
        val fw = image.firmwareVersion?.removePrefix("Ver ")?.trim()?.takeIf { it.isNotEmpty() }
        val ident = listOfNotNull(image.modelName, fw).joinToString(" ")
        return "Factory read $ident"
    }
}
