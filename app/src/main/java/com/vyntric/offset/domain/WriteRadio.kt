/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.ChannelValidator
import com.vyntric.offset.protocol.CloneProgress
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioException
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.Sleeper
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes channels to the radio under the safety rules:
 * 1. read the full image from the radio first;
 * 2. block when the image's ident does not match the radio;
 * 3. patch only the memory the radio's driver lets Offset write (channels, and on the DM-32UV the
 *    zones, scan lists and TX contacts that refer to them) on top of the radio's own image;
 * 4. save a timestamped backup of the radio's image before writing;
 * 5. read back after writing and compare;
 * 7. report every failure, saying whether the radio may be partially written.
 * Rule 6 (confirmation) is the caller's job; [Request.confirmedIdentity] carries what was shown.
 */
class WriteRadio(
    private val link: RadioLink,
    private val library: ImageLibrary,
    private val session: RadioSession,
    private val clock: Clock,
    private val sleeper: Sleeper,
) {
    sealed interface Content {
        /** Up to the radio's capacity of slots, index = radio slot; null slots (and slots past the end) are cleared. */
        data class Channels(val channels: List<Channel?>) : Content

        /** Copy the writable memory of a stored image ("Restore to radio"). */
        data class FromImage(val image: RadioImage) : Content
    }

    data class Request(
        val content: Content,
        /** Name for the resulting image in the library. */
        val resultName: String,
        /** Identity of the radio named on the confirmation sheet, if one had been read. */
        val confirmedIdentity: String?,
        /** The radio type to talk to: the one the staging list was built for, or the restored image's. */
        val driver: RadioDriver,
    )

    enum class Stage { READ, CHECK, BACKUP, WRITE, VERIFY }

    sealed interface Outcome {
        /** [savedImageId] is null if the result could not be stored; [saveError] then says why. */
        data class Verified(val savedImageId: Long?, val backupImageId: Long, val blocksWritten: Int, val saveError: String? = null) : Outcome

        /** Written and read back, but some channels differ from what was sent. */
        data class Mismatch(val channels: List<Int>, val savedImageId: Long?, val backupImageId: Long, val saveError: String? = null) : Outcome

        /** Stopped before anything was written. The radio is unchanged. */
        data class Blocked(val stage: Stage, val reasons: List<String>) : Outcome

        /**
         * Failed after writing started (or during verification). [radioMayBePartial] means the
         * radio may hold a mix of old and new channels and should be restored from the backup.
         */
        data class Failed(val stage: Stage, val message: String, val backupImageId: Long?, val radioMayBePartial: Boolean) : Outcome
    }

    suspend operator fun invoke(request: Request, onProgress: (CloneProgress) -> Unit): Outcome {
        val driver = request.driver
        // Rule 1: full read of the connected radio.
        val radio = try {
            link.session(driver) { port -> driver.clone(port, sleeper, onProgress).download() }
        } catch (e: Exception) {
            return Outcome.Blocked(Stage.READ, listOf(describe(e)))
        }
        session.update(radio, clock.millis())

        // Rule 2: the radio must be the one confirmed, and a restored image must match it.
        val reasons = ArrayList<String>()
        if (request.confirmedIdentity != null && request.confirmedIdentity != radio.identity) {
            reasons += "The connected radio (${radio.identity}) is not the one shown on the confirmation sheet " +
                "(${request.confirmedIdentity}). Nothing was written."
        }
        val target: RadioImage = when (val c = request.content) {
            is Content.FromImage -> {
                if (c.image.driver != radio.driver) {
                    return Outcome.Blocked(
                        Stage.CHECK,
                        listOf("This image is for a ${c.image.driver.displayName}; the radio is a ${radio.driver.displayName}."),
                    )
                }
                if (c.image.identity != radio.identity) {
                    reasons += "This image was read from a different radio (image ${c.image.identity}, " +
                        "radio ${radio.identity}). Offset only restores an image to a radio of the same kind."
                }
                if (c.image.modelName != radio.modelName) {
                    reasons += "This image is for a ${c.image.modelName}; the radio is a ${radio.modelName}."
                }
                // Exact bytes are restored, but nothing that would misprogram this radio: every
                // frequency must be in its bands and every tone valid. Names and step size are not
                // checked, so a radio's own backup always restores. Digital channels are kept as
                // stored.
                c.image.channels().forEachIndexed { i, ch ->
                    if (ch == null || ch.digitalMode != null) return@forEachIndexed
                    ChannelValidator.validate(ch, radio.profile)
                        .filter { it.field in RESTORE_CHECKS && (it.field != ChannelValidator.Field.FREQUENCY || !radio.profile.inBand(ch.rxFreqHz)) }
                        .forEach { p -> reasons += "Channel ${i + 1}: ${p.message}" }
                }
                radio.withWritableRegionsOf(c.image)
            }
            is Content.Channels -> {
                if (c.channels.size > radio.capacity) {
                    reasons += "The radio holds ${radio.capacity} channels; ${c.channels.size} are staged."
                }
                // Only what changes is checked: a channel already on the radio in that slot is
                // written back byte for byte, whatever the radio accepts there.
                c.channels.forEachIndexed { i, ch ->
                    if (ch == null || (i < radio.capacity && ch == radio.channel(i))) return@forEachIndexed
                    ChannelValidator.validate(ch, radio.profile).forEach { p -> reasons += "Channel ${i + 1}: ${p.message}" }
                }
                if (reasons.isEmpty()) {
                    try {
                        radio.withChannels(c.channels)
                    } catch (e: IllegalArgumentException) {
                        return Outcome.Blocked(Stage.CHECK, listOf("A channel could not be encoded: ${e.message}"))
                    }
                } else radio
            }
        }
        // Rule 3: nothing outside the writable memory may change.
        if (!radio.differsOnlyInWritableRegions(target)) {
            reasons += "Internal check failed: the new image would change settings or calibration."
        }
        if (reasons.isNotEmpty()) return Outcome.Blocked(Stage.CHECK, reasons)

        // Rule 4: backup of exactly what the radio holds now.
        val backupId = try {
            library.save(radio, "Backup before write ${stamp()}", ImageKind.BACKUP, notes = "Radio contents before a write.")
        } catch (e: Exception) {
            return Outcome.Blocked(Stage.BACKUP, listOf("Could not save the backup, so nothing was written. ${e.message.orEmpty()}"))
        }

        var blocks = 0
        if (target != radio) {
            try {
                blocks = link.session(driver) { port -> driver.clone(port, sleeper, onProgress).upload(target, before = radio) }
            } catch (e: Exception) {
                // A refusal before any block went out leaves the radio untouched.
                val untouched = e is RadioException.IdentMismatch || e is RadioException.NoContact ||
                    e is RadioException.Unsupported || e is LinkUnavailableException
                return Outcome.Failed(Stage.WRITE, describe(e), backupId, radioMayBePartial = !untouched)
            }
        }

        // Rule 5: read back and compare.
        val readBack = try {
            link.session(driver) { port -> driver.clone(port, sleeper, onProgress).download(CloneProgress.Phase.VERIFYING) }
        } catch (e: Exception) {
            return Outcome.Failed(
                Stage.VERIFY,
                "The write finished but the radio could not be read back to verify it. ${describe(e)}",
                backupId,
                radioMayBePartial = false,
            )
        }
        session.update(readBack, clock.millis())
        val mismatches = target.mismatchedChannels(readBack)
        // The radio is already written; a storage failure here must not hide the result.
        var saveError: String? = null
        val savedId = try {
            library.save(
                readBack, request.resultName.ifBlank { "Written ${stamp()}" }, ImageKind.USER,
                notes = if (mismatches.isEmpty()) "Written and verified ${stamp()}." else "Written ${stamp()}; verification found differences.",
            )
        } catch (e: Exception) {
            saveError = "The radio was written, but the result could not be saved to Images: ${e.message ?: e.javaClass.simpleName}"
            null
        }
        return if (mismatches.isEmpty()) {
            Outcome.Verified(savedId, backupId, blocks, saveError)
        } else {
            Outcome.Mismatch(mismatches.map { it + 1 }, savedId, backupId, saveError)
        }
    }

    private fun stamp(): String = LocalDateTime.now(clock).format(STAMP)

    companion object {
        private val RESTORE_CHECKS = setOf(
            ChannelValidator.Field.FREQUENCY, ChannelValidator.Field.TX_FREQUENCY,
            ChannelValidator.Field.TX_TONE, ChannelValidator.Field.RX_TONE,
        )
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun describe(e: Throwable): String = when (e) {
            is RadioException, is LinkUnavailableException -> e.message.orEmpty()
            else -> "Unexpected error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
