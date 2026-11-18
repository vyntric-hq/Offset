/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.data.db.ReachItemDao
import com.vyntric.offset.data.db.ReachItemEntity
import com.vyntric.offset.domain.reach.Antenna
import com.vyntric.offset.domain.reach.Inventory
import com.vyntric.offset.domain.reach.Place
import com.vyntric.offset.domain.reach.Radio
import com.vyntric.offset.domain.reach.RepeaterAssumptions
import com.vyntric.offset.domain.reach.Station
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * The Reach inventory. Each item is a JSON row tagged with its kind; the row id is the item's id.
 * A row that no longer decodes (written by a newer version) is skipped, never fatal.
 */
class InventoryRepository(
    private val dao: ReachItemDao,
    private val clock: Clock,
    /** Runs a block in one database transaction. */
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observe(): Flow<Inventory> = dao.observeAll().map { rows ->
        Inventory(
            radios = rows.decode(RADIO, Radio.serializer()) { r, id -> r.copy(id = id) },
            antennas = rows.decode(ANTENNA, Antenna.serializer()) { a, id -> a.copy(id = id) },
            stations = rows.decode(STATION, Station.serializer()) { s, id -> s.copy(id = id) },
            places = rows.decode(PLACE, Place.serializer()) { p, id -> p.copy(id = id) },
            repeaterAssumptions = rows.decode(ASSUMPTIONS, RepeaterAssumptions.serializer()) { a, _ -> a }.firstOrNull()
                ?: RepeaterAssumptions(),
        )
    }

    suspend fun save(radio: Radio): Long = put(RADIO, radio.id, json.encodeToString(Radio.serializer(), radio))
    suspend fun save(antenna: Antenna): Long = put(ANTENNA, antenna.id, json.encodeToString(Antenna.serializer(), antenna))
    suspend fun save(station: Station): Long = put(STATION, station.id, json.encodeToString(Station.serializer(), station))
    suspend fun save(place: Place): Long =
        put(PLACE, place.id, json.encodeToString(Place.serializer(), if (place.createdAt == 0L) place.copy(createdAt = clock.millis()) else place))

    suspend fun saveAll(places: List<Place>) = transaction { places.forEach { save(it) } }

    /**
     * Saves a station with its radio and antenna (adding them when new) in one transaction, so a
     * failure never leaves a station pointing at equipment that was not saved. Returns its id.
     */
    suspend fun saveStation(station: Station, radio: Radio, antenna: Antenna): Long {
        var id = 0L
        transaction {
            val radioId = if (radio.id != 0L) radio.id.also { save(radio) } else save(radio)
            val antennaId = if (antenna.id != 0L) antenna.id.also { save(antenna) } else save(antenna)
            id = save(station.copy(radioId = radioId, antennaId = antennaId))
        }
        return id
    }

    /** There is one set of assumptions: read and write in one transaction so two saves never make two rows. */
    suspend fun save(assumptions: RepeaterAssumptions) = transaction {
        val existing = dao.firstOfKind(ASSUMPTIONS)
        put(ASSUMPTIONS, existing?.id ?: 0, json.encodeToString(RepeaterAssumptions.serializer(), assumptions))
    }

    /** Records that [station] was just used, so it opens first next time. */
    suspend fun markUsed(station: Station) = save(station.copy(lastUsedAt = clock.millis()))

    suspend fun delete(id: Long) = dao.delete(id)

    private suspend fun put(kind: String, id: Long, payload: String): Long {
        val now = clock.millis()
        return if (id == 0L) dao.insert(ReachItemEntity(kind = kind, json = payload, updatedAt = now)) else {
            dao.update(id, payload, now)
            id
        }
    }

    private fun <T> List<ReachItemEntity>.decode(kind: String, serializer: KSerializer<T>, withId: (T, Long) -> T): List<T> =
        filter { it.kind == kind }.mapNotNull { row ->
            try {
                withId(json.decodeFromString(serializer, row.json), row.id)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    private companion object {
        const val RADIO = "radio"
        const val ANTENNA = "antenna"
        const val STATION = "station"
        const val PLACE = "place"
        const val ASSUMPTIONS = "repeater-assumptions"
    }
}
