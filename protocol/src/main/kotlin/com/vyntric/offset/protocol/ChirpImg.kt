/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 * File format ported from CHIRP (chirp/chirp_common.py CloneModeRadio.save_mmap and
 * _strip_metadata), GPL.
 */
package com.vyntric.offset.protocol

import java.util.Base64

/**
 * CHIRP's .img file: the raw image followed by a metadata trailer.
 * ```
 * <image bytes> 00 FF 'c' 'h' 'i' 'r' 'p' EE 'i' 'm' 'g' 00 01 <base64(JSON)>
 * ```
 * CHIRP writes the JSON with Python's default separators (", " and ": "); we match that.
 */
object ChirpImg {
    val MAGIC: ByteArray = byteArrayOf(0x00, 0xFF.toByte()) + "chirp".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0xEE.toByte()) + "img".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x01)

    data class Parsed(val image: RadioImage, val metadata: Map<String, String>)

    fun encode(image: RadioImage): ByteArray {
        val fields = linkedMapOf<String, String?>("mem_extra" to null)
        image.chirpMetadata.forEach { (k, v) -> fields[k] = v }
        val json = fields.entries.joinToString(", ", "{", "}") { (k, v) ->
            "\"$k\": " + if (v == null) "{}" else "\"${escape(v)}\""
        }
        val trailer = Base64.getEncoder().encode(json.toByteArray(Charsets.UTF_8))
        return image.bytes() + MAGIC + trailer
    }

    /**
     * Parses a CHIRP .img (with or without trailer) or a bare image dump. The family comes from
     * the metadata model when there is one, otherwise from the image's size and shape.
     */
    fun decode(file: ByteArray): Parsed {
        val idx = indexOf(file, MAGIC)
        val raw = if (idx >= 0) file.copyOfRange(0, idx) else file
        val metadata = if (idx >= 0) parseMetadata(file.copyOfRange(idx + MAGIC.size, file.size)) else emptyMap()
        val model = metadata["model"]
        val driver = if (model != null) {
            RadioDrivers.forChirpModel(model)
                ?: throw InvalidImageException("This .img is for a $model, which Offset does not support.")
        } else {
            RadioDrivers.recognize(raw)
                ?: throw InvalidImageException("Offset does not recognize this ${raw.size} byte image.")
        }
        return Parsed(driver.image(raw), metadata)
    }

    /** Extracts flat string values from the metadata JSON. Nested objects are ignored. */
    private fun parseMetadata(blob: ByteArray): Map<String, String> {
        val text = try {
            String(Base64.getMimeDecoder().decode(blob), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return emptyMap()
        }
        val pattern = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.findAll(text).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
