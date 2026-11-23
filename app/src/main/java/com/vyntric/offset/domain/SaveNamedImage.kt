/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.RadioImage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saves a user image under a name the way Finder names copies. If an image in the name's family
 * ("Trip", "Trip (2)", ...) already holds the same channels for the same radio type, nothing is
 * saved and that image is returned. Otherwise the image takes the name, or the first free
 * "Name (n)". Only channels are compared: the radio changes some settings on its own, such as
 * the VFO frequencies, and a set is its channels.
 */
class SaveNamedImage(private val library: ImageLibrary) {
    data class Saved(val id: Long, val name: String, val existing: Boolean)

    /** One save at a time, so two saves of the same set cannot both decide the name is free. */
    private val mutex = Mutex()

    suspend operator fun invoke(image: RadioImage, name: String, notes: String = ""): Saved = mutex.withLock {
        val base = baseOf(name.trim().ifEmpty { UNTITLED })
        val family = library.userImagesNamed(base, COMPARED)
        val channels = image.channels()
        family.recent.firstOrNull { it.image.driver == image.driver && it.image.channels() == channels }
            ?.let { return@withLock Saved(it.id, it.name, existing = true) }
        val taken = family.names
        val free = if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
        Saved(library.save(image, free, ImageKind.USER, notes), free, existing = false)
    }

    companion object {
        const val UNTITLED = "Untitled"

        /** How many of a family's newest images are compared; an older match only costs a numbered copy. */
        const val COMPARED = 50
        private val NUMBERED = Regex("""^(.*\S) \((\d+)\)$""")

        /** "Trip (2)" is in the family of "Trip". */
        fun baseOf(name: String): String = NUMBERED.matchEntire(name)?.groupValues?.get(1) ?: name

        /** True when [name] is [base] or a numbered copy of it. */
        fun inFamily(name: String, base: String): Boolean = name == base || NUMBERED.matchEntire(name)?.groupValues?.get(1) == base
    }
}
