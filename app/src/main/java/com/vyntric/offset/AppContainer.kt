/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset

import android.content.Context
import androidx.room.Room
import com.vyntric.offset.data.CountyDirectory
import com.vyntric.offset.data.CredentialStore
import com.vyntric.offset.data.ImageRepository
import com.vyntric.offset.data.LocationSource
import com.vyntric.offset.data.SettingsRepository
import com.vyntric.offset.data.StagingRepository
import com.vyntric.offset.data.db.OffsetDatabase
import com.vyntric.offset.data.repeaters.CsvImportProvider
import com.vyntric.offset.data.repeaters.HearhamProvider
import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.data.repeaters.RepeaterBookProvider
import com.vyntric.offset.domain.BuildStaging
import com.vyntric.offset.domain.CableState
import com.vyntric.offset.domain.ExportImage
import com.vyntric.offset.domain.ImportImage
import com.vyntric.offset.domain.RadioSession
import com.vyntric.offset.domain.ReadRadio
import com.vyntric.offset.domain.RepeaterProvider
import com.vyntric.offset.domain.RepeaterSource
import com.vyntric.offset.domain.WriteRadio
import com.vyntric.offset.protocol.ChirpImg
import com.vyntric.offset.protocol.Sleeper
import com.vyntric.offset.transport.SimulatedRadioLink
import com.vyntric.offset.transport.SwitchingRadioLink
import com.vyntric.offset.transport.UsbRadioLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** Manual dependency wiring. One instance per process, owned by [OffsetApp]. */
class AppContainer(context: Context, database: OffsetDatabase? = null) {
    private val app = context.applicationContext
    val clock: Clock = Clock.systemDefaultZone()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app.getSharedPreferences("settings", Context.MODE_PRIVATE))
    val credentials = CredentialStore(app)
    val location = LocationSource(app)
    val counties = CountyDirectory { app.assets.open("places/counties.tsv") }

    private val db = database ?: Room.databaseBuilder(app, OffsetDatabase::class.java, "offset.db").build()
    val images = ImageRepository(db.images(), clock)
    val staging = StagingRepository(db.staging(), clock)

    val usbLink = UsbRadioLink(app)
    val simulatedLink = SimulatedRadioLink(
        stateFile = File(app.noBackupFilesDir, "simulated-radio.img"),
        factoryImage = { ChirpImg.decode(app.assets.open("simulated/uv5r-factory.img").use { it.readBytes() }).image },
    )

    /** Simulation is a debug-only feature; release builds always use USB. */
    private fun simulated() = BuildConfig.DEBUG && settings.settings.value.simulatedRadio

    val radioLink = SwitchingRadioLink(
        usb = usbLink,
        simulated = simulatedLink,
        useSimulated = ::simulated,
        scopeCable = combine(usbLink.cable, simulatedLink.cable, settings.settings) { usb, sim, s ->
            if (BuildConfig.DEBUG && s.simulatedRadio) sim else usb
        }.stateIn(appScope, SharingStarted.Eagerly, CableState.NoCable),
    )

    val radioSession = RadioSession()
    private val sleeper = Sleeper.REAL
    val readRadio = ReadRadio(radioLink, images, radioSession, clock, sleeper)
    val writeRadio = WriteRadio(radioLink, images, radioSession, clock, sleeper)
    val operations = com.vyntric.offset.domain.RadioOperations(appScope, readRadio, writeRadio)
    private val keys = AtomicLong(clock.millis())
    val buildStaging = BuildStaging { keys.incrementAndGet() }
    val importImage = ImportImage(images)
    val exportImage = ExportImage()

    private val userAgentBase = "Offset/${BuildConfig.VERSION_NAME} (${BuildConfig.GITHUB_REPO}"
    private val repeaterDir = File(app.noBackupFilesDir, "repeaters")
    val hearham = HearhamProvider(
        Http.publicClient(), File(repeaterDir, "hearham.json"), "$userAgentBase)", clock, bundle = bundledHearham(app),
    )
    val repeaterBook = RepeaterBookProvider(
        Http.authenticatedClient(), File(repeaterDir, "repeaterbook"), { credentials.credentials.value }, userAgentBase, clock,
    )
    val csvProvider = CsvImportProvider(File(repeaterDir, "imported-csv.json"), clock)

    init {
        // Unplugging the cable forgets the radio, so nothing is written against a stale read.
        appScope.launch {
            radioLink.cable.collect { if (it is CableState.NoCable) radioSession.clear() }
        }
    }

    /** The Hearham snapshot in assets/repeaters, written by scripts/update_repeater_bundle.py. */
    private fun bundledHearham(context: Context): HearhamProvider.Bundle? = runCatching {
        val meta = context.assets.open("repeaters/hearham.meta.json").use { it.readBytes().decodeToString() }
        val fetchedAt = kotlinx.serialization.json.Json.parseToJsonElement(meta)
            .let { (it as kotlinx.serialization.json.JsonObject)["fetchedAt"].toString().toLong() }
        // AAPT decompresses ".gz" assets and drops the suffix, so the APK holds hearham.json.
        val names = context.assets.list("repeaters").orEmpty()
        when {
            "hearham.json" in names -> HearhamProvider.Bundle(fetchedAt, gzipped = false) { context.assets.open("repeaters/hearham.json") }
            "hearham.json.gz" in names -> HearhamProvider.Bundle(fetchedAt, gzipped = true) { context.assets.open("repeaters/hearham.json.gz") }
            else -> null
        }
    }.getOrNull()

    fun provider(source: RepeaterSource): RepeaterProvider = when (source) {
        RepeaterSource.HEARHAM -> hearham
        RepeaterSource.REPEATERBOOK -> repeaterBook
        RepeaterSource.CSV -> csvProvider
    }
}
