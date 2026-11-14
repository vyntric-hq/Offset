/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.data.db.ImageDao
import com.vyntric.offset.data.db.ImageEntity
import com.vyntric.offset.data.db.ImageSummaryRow
import com.vyntric.offset.domain.ImageKind
import com.vyntric.offset.domain.ImageLibrary
import com.vyntric.offset.domain.StoredImage
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioDrivers
import com.vyntric.offset.protocol.RadioImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

data class ImageSummary(
    val id: Long,
    val name: String,
    val kind: ImageKind,
    /** The radio family, or null for a family this version of Offset does not know. */
    val driver: RadioDriver?,
    val identHex: String,
    val model: String,
    val firmware: String?,
    val channelCount: Int,
    val notes: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** The image library, stored in Room. All image persistence goes through here. */
class ImageRepository(private val dao: ImageDao, private val clock: Clock) : ImageLibrary {

    fun observeAll(): Flow<List<ImageSummary>> = dao.observeSummaries(MAX_LISTED).map { rows -> rows.map { it.toSummary() } }

    fun observe(id: Long): Flow<StoredImage?> = dao.observe(id).map { it?.toStored() }

    override suspend fun load(id: Long): StoredImage? = dao.get(id)?.toStored()

    override suspend fun hasFactoryImage(identity: String): Boolean = dao.countFactory(identity) > 0

    override suspend fun save(image: RadioImage, name: String, kind: ImageKind, notes: String): Long {
        val now = clock.millis()
        return dao.insert(
            ImageEntity(
                name = name.trim().ifEmpty { "Untitled image" },
                kind = kind.name,
                radio = image.driver.id,
                identHex = image.identity,
                model = image.modelName,
                firmware = image.firmwareVersion,
                channelCount = image.usedChannelCount,
                notes = notes,
                createdAt = now,
                updatedAt = now,
                bytes = image.bytes(),
            )
        )
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim().ifEmpty { "Untitled image" }, clock.millis())

    suspend fun updateNotes(id: Long, notes: String) = dao.updateNotes(id, notes, clock.millis())

    suspend fun updateImage(id: Long, image: RadioImage) = dao.updateBytes(id, image.bytes(), image.usedChannelCount, clock.millis())

    suspend fun duplicate(id: Long): Long? {
        val src = load(id) ?: return null
        return save(src.image, "${src.name} copy", ImageKind.USER, src.notes)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun ImageSummaryRow.toSummary() = ImageSummary(
        id, name, kindOf(kind), RadioDrivers.byId(radio), identHex, model, firmware, channelCount, notes, createdAt, updatedAt,
    )

    private fun ImageEntity.toStored() =
        StoredImage(id, name, kindOf(kind), notes, createdAt, updatedAt, RadioDrivers.image(radio, bytes))

    private fun kindOf(s: String) = runCatching { ImageKind.valueOf(s) }.getOrDefault(ImageKind.USER)

    companion object {
        /** Library lists are bounded; a few hundred images is far beyond normal use. */
        const val MAX_LISTED = 1000
    }
}
