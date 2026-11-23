/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.RadioImage

enum class ImageKind {
    /** Saved by the user, including results of a write. */
    USER,

    /** The first image ever read from a radio with this ident. */
    FACTORY,

    /** Taken automatically before every write. */
    BACKUP,
}

data class StoredImage(
    val id: Long,
    val name: String,
    val kind: ImageKind,
    val notes: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val image: RadioImage,
)

/** What the use cases need from image storage. Implemented over Room in the data layer. */
interface ImageLibrary {
    suspend fun save(image: RadioImage, name: String, kind: ImageKind, notes: String = ""): Long
    suspend fun load(id: Long): StoredImage?
    suspend fun hasFactoryImage(identity: String): Boolean

    /**
     * The names of user images named [base] or a numbered copy of it ("[base] (2)"), and the most
     * recent [recent] of those images, see [SaveNamedImage].
     */
    suspend fun userImagesNamed(base: String, recent: Int): NameFamily
}

/** Every name taken in a name's family, and its most recent images for comparing channels. */
data class NameFamily(val names: Set<String>, val recent: List<StoredImage>) {
    companion object {
        val EMPTY = NameFamily(emptySet(), emptyList())
    }
}
