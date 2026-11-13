/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.transport

import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.RadioLink
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.SerialPort
import com.vyntric.offset.protocol.SimulatedRadio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A simulated UV-5R for debug builds and tests. Its memory persists in [stateFile] so a write
 * survives an app restart, like a real radio.
 *
 * Each byte is delivered without delay; [pacingMs] adds a short pause per session so progress UI
 * is visible.
 */
class SimulatedRadioLink(
    private val stateFile: File,
    private val factoryImage: () -> RadioImage,
    private val pacingMs: Long = 600,
) : RadioLink {
    private val _cable = MutableStateFlow<CableState>(CableState.Simulated("Simulated UV-5R"))
    override val cable: StateFlow<CableState> = _cable.asStateFlow()

    private val mutex = Mutex()
    private var radio: SimulatedRadio? = null

    override fun requestPermission() = Unit

    override suspend fun <T> session(block: (SerialPort) -> T): T = mutex.withLock {
        if (pacingMs > 0) delay(pacingMs)
        withContext(Dispatchers.IO) {
            val r = radio ?: load().also { radio = it }
            try {
                block(r)
            } finally {
                r.endSession()
                persist(r)
            }
        }
    }

    /** Puts the simulated radio back to its factory image. */
    suspend fun reset() = mutex.withLock {
        withContext(Dispatchers.IO) {
            stateFile.delete()
            radio = null
        }
    }

    private fun load(): SimulatedRadio {
        val saved = stateFile.takeIf { it.exists() }?.readBytes()?.let { runCatching { RadioImage.of(it) }.getOrNull() }
        return SimulatedRadio.fromImage(saved ?: factoryImage())
    }

    private fun persist(r: SimulatedRadio) {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeBytes(r.currentImage().bytes())
        tmp.renameTo(stateFile)
    }
}

/** Routes to the simulated radio when the debug setting is on, otherwise to USB. */
class SwitchingRadioLink(
    private val usb: UsbRadioLink,
    private val simulated: SimulatedRadioLink,
    private val useSimulated: () -> Boolean,
    scopeCable: StateFlow<CableState>,
) : RadioLink {
    override val cable: StateFlow<CableState> = scopeCable

    private fun active(): RadioLink = if (useSimulated()) simulated else usb

    override fun requestPermission() = active().requestPermission()

    override suspend fun <T> session(block: (SerialPort) -> T): T = active().session(block)
}
