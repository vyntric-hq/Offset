/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.protocol

import java.io.IOException

/**
 * A byte pipe to the radio at 9600 8N1. Implemented by the USB transport in the app and by
 * [SimulatedRadio] for tests and the no-hardware debug mode.
 */
interface SerialPort {
    /** Writes all bytes or throws [IOException] (for example when the cable is unplugged). */
    @Throws(IOException::class)
    fun write(data: ByteArray)

    /**
     * Reads up to [count] bytes, waiting at most [timeoutMs] in total. Returns fewer bytes
     * (possibly none) on timeout. Throws [IOException] if the link is gone.
     */
    @Throws(IOException::class)
    fun read(count: Int, timeoutMs: Long): ByteArray

    /** Discards any unread input. */
    @Throws(IOException::class)
    fun purge()
}

/** Injected delay so protocol timing is real on hardware and instant in tests. */
fun interface Sleeper {
    fun sleep(ms: Long)

    companion object {
        val REAL = Sleeper { Thread.sleep(it) }
        val NONE = Sleeper { }
    }
}

/** Failures talking to a radio. Every message is written to be shown to the user. */
sealed class RadioException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoContact(message: String = DEFAULT) : RadioException(message) {
        companion object {
            const val DEFAULT =
                "The radio did not answer. Check that it is on, the cable is fully seated in the radio, " +
                    "and the volume is turned up. A radio restarts for a few seconds after it is read or " +
                    "written, so if you just did either, wait 10 seconds and try again."
        }
    }
    class Protocol(message: String) : RadioException(message)
    class Timeout(message: String) : RadioException(message)
    class Disconnected(cause: Throwable?) : RadioException("The cable was disconnected.", cause)
    class IdentMismatch(message: String) : RadioException(message)
    class Unsupported(message: String) : RadioException(message)
}

/** Progress for a clone operation, in image bytes. */
data class CloneProgress(val phase: Phase, val bytesDone: Int, val bytesTotal: Int) {
    enum class Phase { IDENTIFYING, READING, WRITING, VERIFYING }

    val fraction: Float get() = if (bytesTotal == 0) 0f else bytesDone.toFloat() / bytesTotal
}
