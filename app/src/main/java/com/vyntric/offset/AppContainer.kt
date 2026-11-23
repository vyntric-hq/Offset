/*
 * Offset, an Android radio programming client.
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
import androidx.room.withTransaction
import com.vyntric.offset.data.InventoryRepository
import com.vyntric.offset.data.TerrainRepository
import com.vyntric.offset.data.repeaters.CsvImportProvider
import com.vyntric.offset.data.repeaters.HearhamProvider
import com.vyntric.offset.data.repeaters.Http
import com.vyntric.offset.data.repeaters.RepeaterBookProvider
import com.vyntric.offset.domain.CombinedRepeaterProvider
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
import com.vyntric.offset.protocol.RadioDriver
import com.vyntric.offset.protocol.RadioDrivers
import com.vyntric.offset.protocol.Uv5rDriver
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

    private val db = database ?: Room.databaseBuilder(app, OffsetDatabase::class.java, "offset.db")
        .addMigrations(*OffsetDatabase.MIGRATIONS).build()
    val images = ImageRepository(db.images(), clock)
    val staging = StagingRepository(db.staging(), clock)
    val inventory = InventoryRepository(db.reachItems(), clock) { block -> db.withTransaction { block() } }

    val usbLink = UsbRadioLink(app)
    val simulatedLink = SimulatedRadioLink(
        stateFile = { d -> File(app.noBackupFilesDir, if (d == Uv5rDriver) "simulated-radio.img" else "simulated-${d.id}.img") },
        factoryImage = { d ->
            if (d == Uv5rDriver) ChirpImg.decode(app.assets.open("simulated/uv5r-factory.img").use { it.readBytes() }).image
            else d.sampleImage()
        },
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
    /** The radio type selected for reading; writes use the type of the radio that was read. */
    fun selectedDriver(): RadioDriver = RadioDrivers.byId(settings.settings.value.radioType) ?: RadioDrivers.default

    /** Selects the radio type to read and build staging for. A radio read as another type is forgotten. */
    fun selectRadioType(driver: RadioDriver) {
        if (settings.settings.value.radioType != driver.id) settings.update { it.copy(radioType = driver.id) }
        if (radioSession.last.value?.image?.driver.let { it != null && it != driver }) radioSession.clear()
    }

    val readRadio = ReadRadio(radioLink, images, radioSession, clock, sleeper, ::selectedDriver)

    /** The radio staging is built for: the one last read, else the selected type. */
    fun stagingTarget(): com.vyntric.offset.domain.StagingTarget =
        radioSession.last.value?.image?.let { com.vyntric.offset.domain.StagingTarget.of(it) }
            ?: com.vyntric.offset.domain.StagingTarget.of(selectedDriver())
    val saveNamedImage = com.vyntric.offset.domain.SaveNamedImage(images)
    val writeRadio = WriteRadio(radioLink, images, radioSession, clock, sleeper, saveNamedImage)
    val operations = com.vyntric.offset.domain.RadioOperations(appScope, readRadio, writeRadio)
    private val keys = AtomicLong(clock.millis())
    val buildStaging = BuildStaging { keys.incrementAndGet() }
    val importImage = ImportImage(images)
    val exportImage = ExportImage()

    private val userAgentBase = "Offset/${BuildConfig.VERSION_NAME} (${BuildConfig.GITHUB_REPO}"
    private val repeaterDir = File(app.noBackupFilesDir, "repeaters")
    val hearham = HearhamProvider(
        Http.publicClient(), File(repeaterDir, "hearham.json"), "$userAgentBase)", clock, bundle = bundledHearham(app),
        listings = bundledListings(app),
    )
    val repeaterBook = RepeaterBookProvider(
        Http.authenticatedClient(), File(repeaterDir, "repeaterbook"), { credentials.credentials.value },
        // Until RepeaterBook corrects the approved value, only the exact approved text passes.
        { RepeaterBookProvider.APPROVED_USER_AGENT }, clock,
    )
    val csvProvider = CsvImportProvider(File(repeaterDir, "imported-csv.json"), clock)

    /** Elevation tiles for Reach, kept out of backups like the repeater caches. */
    val terrain = TerrainRepository(TerrainRepository.client(), File(app.noBackupFilesDir, "terrain"), "$userAgentBase)", ::decodeTile)

    private fun decodeTile(bytes: ByteArray): IntArray? {
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            if (bitmap.width != 256 || bitmap.height != 256) null
            else IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
        } finally {
            bitmap.recycle()
        }
    }

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

    /** IRLP and OpenStreetMap listings in assets/repeaters, written by scripts/update_listings_bundle.py. */
    private fun bundledListings(context: Context): (() -> java.io.InputStream)? {
        val names = runCatching { context.assets.list("repeaters").orEmpty() }.getOrDefault(emptyArray())
        return when {
            "listings.json" in names -> { { context.assets.open("repeaters/listings.json") } }
            "listings.json.gz" in names -> { { java.util.zip.GZIPInputStream(context.assets.open("repeaters/listings.json.gz")) } }
            else -> null
        }
    }

    fun provider(source: RepeaterSource): RepeaterProvider = when (source) {
        RepeaterSource.HEARHAM -> hearham
        RepeaterSource.REPEATERBOOK -> repeaterBook
        RepeaterSource.CSV -> csvProvider
        RepeaterSource.IRLP, RepeaterSource.OPENSTREETMAP ->
            throw IllegalArgumentException("${source.displayName} is bundled with Hearham, not a provider")
    }

    /**
     * The chosen sources searched as one. RepeaterBook comes first, so where two sources list a
     * repeater its details win; Hearham and an imported list add what it lacks.
     */
    fun repeaters(sources: Set<RepeaterSource>): CombinedRepeaterProvider =
        CombinedRepeaterProvider(MERGE_ORDER.filter { it in sources }.ifEmpty { listOf(RepeaterSource.HEARHAM) }.map(::provider))

    private companion object {
        val MERGE_ORDER = listOf(RepeaterSource.REPEATERBOOK, RepeaterSource.HEARHAM, RepeaterSource.CSV)
    }
}
