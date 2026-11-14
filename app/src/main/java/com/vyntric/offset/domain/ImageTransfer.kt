/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChirpCsv
import com.vyntric.offset.protocol.ChirpImg
import com.vyntric.offset.protocol.InvalidImageException
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.RadioModel
import com.vyntric.offset.protocol.RadioProfile
import com.vyntric.offset.protocol.Uv5rImage

/** Imports a CHIRP .img (or a bare image dump) into the library. */
class ImportImage(private val library: ImageLibrary) {
    sealed interface Outcome {
        data class Saved(val id: Long, val name: String) : Outcome
        data class Rejected(val message: String) : Outcome
    }

    suspend operator fun invoke(bytes: ByteArray, fileName: String): Outcome {
        val parsed = try {
            ChirpImg.decode(bytes)
        } catch (e: InvalidImageException) {
            return Outcome.Rejected(e.message.orEmpty())
        }
        val image = parsed.image
        val metaModel = parsed.metadata["model"]?.let { RadioModel.fromChirpModel(it) }
        if (image is Uv5rImage && metaModel != null && metaModel != image.model && image.detection.recognized) {
            return Outcome.Rejected(
                "The file says ${metaModel.displayName} but the firmware inside is ${image.model.displayName}."
            )
        }
        val name = fileName.substringBeforeLast('.').ifBlank { "Imported image" }
        val id = library.save(image, name, ImageKind.USER, notes = "Imported from $fileName")
        return Outcome.Saved(id, name)
    }

    /** Applies a CHIRP CSV to [base]: rows land in their Location slots, other slots are untouched. */
    fun applyCsv(base: RadioImage, csv: String): Pair<RadioImage, List<String>> {
        val parsed = ChirpCsv.parse(csv, base.profile)
        val slots: MutableList<Channel?> = base.channels().toMutableList()
        val errors = parsed.errors.toMutableList()
        val first = base.driver.chirpFirstLocation
        for (row in parsed.rows) {
            val index = row.location - first
            if (index !in 0 until base.capacity) {
                errors += "Location ${row.location} is outside $first to ${base.capacity - 1 + first}; skipped."
                continue
            }
            if (slots[index]?.digitalMode != null) {
                errors += "Location ${row.location} holds a ${slots[index]?.digitalMode} channel, which Offset keeps as it is; skipped."
                continue
            }
            slots[index] = row.channel
        }
        return try {
            base.withChannels(slots) to errors
        } catch (e: IllegalArgumentException) {
            base to errors + "The CSV could not be applied: ${e.message}"
        }
    }
}

/** Produces CHIRP compatible files from a stored image. */
class ExportImage {
    fun img(image: RadioImage): ByteArray = ChirpImg.encode(image)

    fun csv(image: RadioImage): String {
        val first = image.driver.chirpFirstLocation
        return ChirpCsv.write(image.channels().withIndex().mapNotNull { (i, c) -> c?.let { i + first to it } }, image.profile)
    }

    fun csv(channels: List<Channel>, profile: RadioProfile): String =
        ChirpCsv.write(channels.withIndex().map { (i, c) -> i to c }, profile)
}
