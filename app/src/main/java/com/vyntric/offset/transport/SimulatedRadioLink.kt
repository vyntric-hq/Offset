/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.transport

import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.RadioLink
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioImage
import com.vyntric.offset.protocol.SerialPort
import com.vyntric.offset.protocol.SimulatedDevice
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
 * Simulated radios for debug builds and tests, one per radio family, each speaking its family's
 * protocol. A radio's memory persists in [stateFile] so a write survives an app restart, like a
 * real radio.
 *
 * Each byte is delivered without delay; [pacingMs] adds a short pause per session so progress UI
 * is visible.
 */
class SimulatedRadioLink(
    private val stateFile: (RadioDriver) -> File,
    private val factoryImage: (RadioDriver) -> RadioImage,
    private val pacingMs: Long = 600,
) : RadioLink {
    private val _cable = MutableStateFlow<CableState>(CableState.Simulated("Simulated radio"))
    override val cable: StateFlow<CableState> = _cable.asStateFlow()

    private val mutex = Mutex()
    private val radios = HashMap<String, SimulatedDevice>()

    override fun requestPermission() = Unit

    override suspend fun <T> session(driver: RadioDriver, block: (SerialPort) -> T): T = mutex.withLock {
        if (pacingMs > 0) delay(pacingMs)
        withContext(Dispatchers.IO) {
            val r = radios.getOrPut(driver.id) { load(driver) }
            try {
                block(r)
            } finally {
                r.endSession()
                persist(driver, r)
            }
        }
    }

    /** Puts every simulated radio back to its factory image. */
    suspend fun reset() = mutex.withLock {
        withContext(Dispatchers.IO) {
            com.vyntric.offset.protocol.RadioDrivers.all.forEach { stateFile(it).delete() }
            radios.clear()
        }
    }

    private fun load(driver: RadioDriver): SimulatedDevice {
        val saved = stateFile(driver).takeIf { it.exists() }?.readBytes()
            ?.let { runCatching { driver.image(it) }.getOrNull() }
        return driver.simulator(saved ?: factoryImage(driver))
    }

    private fun persist(driver: RadioDriver, r: SimulatedDevice) {
        val file = stateFile(driver)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(r.currentImage().bytes())
        tmp.renameTo(file)
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

    override suspend fun <T> session(driver: RadioDriver, block: (SerialPort) -> T): T = active().session(driver, block)
}
