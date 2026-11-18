/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.LinkUnavailableException
import com.vyntric.offset.domain.RadioLink
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The programming cable over USB OTG, through usb-serial-for-android (FTDI, PL2303, CH34x,
 * CP210x, and USB CDC serial devices). Tracks attach, detach and permission, and opens the port
 * at the radio family's speed, 8N1, per session.
 */
class UsbRadioLink(private val context: Context) : RadioLink {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val _cable = MutableStateFlow<CableState>(CableState.NoCable)
    override val cable: StateFlow<CableState> = _cable.asStateFlow()

    private val mutex = Mutex()
    private var lastClosedAt = 0L
    private val denied = HashSet<Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) {
                val device = deviceFrom(intent)
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    denied += device.deviceId
                } else if (device != null) {
                    denied -= device.deviceId
                }
            }
            refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Re-evaluates the cable state; also called when the activity resumes. */
    fun refresh() {
        val driver = findDriver()
        _cable.value = when {
            driver == null -> CableState.NoCable
            usb?.hasPermission(driver.device) == true -> CableState.Ready(describe(driver.device), chipName(driver))
            driver.device.deviceId in denied -> CableState.PermissionDenied(describe(driver.device))
            else -> CableState.NeedsPermission(describe(driver.device))
        }
    }

    override fun requestPermission() {
        val driver = findDriver() ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        // Mutable so the system can attach EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb?.requestPermission(driver.device, pending)
    }

    override suspend fun <T> session(radio: RadioDriver, block: suspend (SerialPort) -> T): T = mutex.withLock {
        // Give the radio a moment between back to back clone sessions (read, write, verify).
        val wait = SESSION_GAP_MS - (System.currentTimeMillis() - lastClosedAt)
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) {
                val manager = usb ?: throw LinkUnavailableException("This device has no USB host support.")
                val driver = findDriver() ?: throw LinkUnavailableException("No programming cable found. Plug it in through the OTG adapter.")
                if (!manager.hasPermission(driver.device)) {
                    throw LinkUnavailableException("Offset needs permission to use the cable. Tap USB permission to grant it.")
                }
                val connection = manager.openDevice(driver.device)
                    ?: throw LinkUnavailableException("Android could not open the cable. Unplug it and plug it back in.")
                val port = driver.ports.first()
                try {
                    port.open(connection)
                    port.setParameters(radio.baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.dtr = true; port.rts = true }
                    block(UsbSerialAdapter(port))
                } finally {
                    try {
                        port.close()
                    } catch (_: IOException) {
                        // Already closed by a detach; nothing else to release.
                    }
                }
            }
        } finally {
            lastClosedAt = System.currentTimeMillis()
            refresh()
        }
    }

    private fun findDriver(): UsbSerialDriver? {
        val manager = usb ?: return null
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
    }

    private fun describe(d: UsbDevice): String = listOfNotNull(d.manufacturerName, d.productName)
        .joinToString(" ").ifBlank { "USB serial %04X:%04X".format(d.vendorId, d.productId) }

    private fun chipName(driver: UsbSerialDriver): String = driver.javaClass.simpleName.removeSuffix("SerialDriver")

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val ACTION_PERMISSION = "com.vyntric.offset.USB_PERMISSION"
        const val SESSION_GAP_MS = 1_500L
    }
}

/** Adapts a usb-serial port to the protocol's [SerialPort], buffering surplus bytes. */
internal class UsbSerialAdapter(private val port: UsbSerialPort) : SerialPort {
    private val pending = ArrayDeque<Byte>()
    private val buffer = ByteArray(maxOf(port.readEndpoint?.maxPacketSize ?: 64, 64))

    override fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun read(count: Int, timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.size < count) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val n = port.read(buffer, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1))
            for (i in 0 until n) pending.addLast(buffer[i])
        }
        val take = minOf(count, pending.size)
        return ByteArray(take) { pending.removeFirst() }
    }

    override fun purge() {
        pending.clear()
        runCatching { port.purgeHwBuffers(true, true) }
        // Drain anything already in flight.
        while (port.read(buffer, 20) > 0) Unit
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
    }
}
