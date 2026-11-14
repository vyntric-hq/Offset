/*
 * Offset, a UV-5R programming client.
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
}
