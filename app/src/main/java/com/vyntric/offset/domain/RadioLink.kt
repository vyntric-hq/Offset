/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.SerialPort
import kotlinx.coroutines.flow.StateFlow

/** What the app knows about the programming cable right now. */
sealed interface CableState {
    data object NoCable : CableState
    data class NeedsPermission(val deviceName: String) : CableState
    data class PermissionDenied(val deviceName: String) : CableState
    data class Ready(val deviceName: String, val chip: String) : CableState
    data class Simulated(val description: String) : CableState
}

/**
 * The connection to a radio. Two implementations: the USB programming cable and the simulated
 * radio used in debug builds and tests.
 */
interface RadioLink {
    val cable: StateFlow<CableState>

    /** Asks the OS for permission to use the attached cable. No-op when not applicable. */
    fun requestPermission()

    /**
     * Opens the port for a radio of [driver]'s family (its serial speed, or its simulator), runs
     * [block] (blocking protocol code) on a background thread and always closes the port. Only
     * one session runs at a time. [block] may suspend (to save a backup mid session); the port
     * stays open meanwhile.
     *
     * @throws LinkUnavailableException when there is no usable cable.
     */
    suspend fun <T> session(driver: RadioDriver, block: suspend (SerialPort) -> T): T
}

class LinkUnavailableException(message: String) : Exception(message)
