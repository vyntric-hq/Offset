/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChirpCsv
import com.vyntric.offset.protocol.ChirpImg
import com.vyntric.offset.protocol.InvalidImageException
import com.vyntric.offset.protocol.RadioImage

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
        val metaModel = parsed.metadataModel
        if (metaModel != null && metaModel != parsed.image.model && parsed.image.detection.recognized) {
            return Outcome.Rejected(
                "The file says ${metaModel.displayName} but the firmware inside is ${parsed.image.model.displayName}."
            )
        }
        val name = fileName.substringBeforeLast('.').ifBlank { "Imported image" }
        val id = library.save(parsed.image, name, ImageKind.USER, notes = "Imported from $fileName")
        return Outcome.Saved(id, name)
    }

    /** Applies a CHIRP CSV to [base]: rows land in their Location slots, other slots are untouched. */
    fun applyCsv(base: RadioImage, csv: String): Pair<RadioImage, List<String>> {
        val parsed = ChirpCsv.parse(csv, base.model)
        val slots: MutableList<Channel?> = base.channels().toMutableList()
        val errors = parsed.errors.toMutableList()
        for (row in parsed.rows) {
            if (row.location !in 0 until RadioImage.CHANNEL_COUNT) {
                errors += "Location ${row.location} is outside 0 to ${RadioImage.CHANNEL_COUNT - 1}; skipped."
                continue
            }
            slots[row.location] = row.channel
        }
        return base.withChannels(slots) to errors
    }
}

/** Produces CHIRP compatible files from a stored image. */
class ExportImage {
    fun img(image: RadioImage): ByteArray = ChirpImg.encode(image)

    fun csv(image: RadioImage): String =
        ChirpCsv.write(image.channels().withIndex().mapNotNull { (i, c) -> c?.let { i to it } }, image.model)

    fun csv(channels: List<Channel>, image: RadioImage?): String =
        ChirpCsv.write(channels.withIndex().map { (i, c) -> i to c }, image?.model ?: com.vyntric.offset.protocol.RadioModel.UV5R)
}
