/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import com.vyntric.offset.data.db.StagingDao
import com.vyntric.offset.data.db.StagingEntity
import com.vyntric.offset.domain.ChannelOrigin
import com.vyntric.offset.domain.StagedChannel
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.Duplex
import com.vyntric.offset.protocol.Modulation
import com.vyntric.offset.protocol.Power
import com.vyntric.offset.protocol.PttId
import com.vyntric.offset.protocol.Squelch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/** Persists the staging list so it survives process death. */
class StagingRepository(private val dao: StagingDao, private val clock: Clock) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    fun observe(): Flow<List<StagedChannel>> = dao.observe().map { decode(it?.json) }

    suspend fun get(): List<StagedChannel> = decode(dao.get()?.json)

    suspend fun put(list: List<StagedChannel>) = mutex.withLock { write(list) }

    /**
     * Atomically replaces the list with [transform] of the current list. Concurrent edits (for
     * example a burst of drag moves) are applied one after another, never lost.
     */
    suspend fun <R> update(transform: (List<StagedChannel>) -> Pair<List<StagedChannel>, R>): R = mutex.withLock {
        val (next, result) = transform(get())
        write(next)
        result
    }

    private suspend fun write(list: List<StagedChannel>) {
        dao.put(StagingEntity(json = json.encodeToString(list.map { it.toDto() }), updatedAt = clock.millis()))
    }

    private fun decode(text: String?): List<StagedChannel> {
        if (text.isNullOrEmpty()) return emptyList()
        val list = json.decodeFromString<List<StagedDto>>(text).map { it.toDomain() }
        // Lists saved before slots existed (slot -1, UV-5R only) are numbered in their saved order.
        val used = list.map { it.slot }.filter { it >= 0 }.toMutableSet()
        return list.map { s ->
            if (s.slot >= 0) s else {
                val free = generateSequence(0) { it + 1 }.first { it !in used }
                used += free
                s.copy(slot = free)
            }
        }.sortedBy { it.slot }
    }

    @Serializable
    internal data class StagedDto(
        val key: Long,
        val origin: String,
        val note: String = "",
        val rx: Long,
        val duplex: String,
        val offset: Long,
        val tx: String,
        val rxTone: String,
        val power: String,
        val wide: Boolean,
        val scan: Boolean,
        val bcl: Boolean,
        val pttId: String,
        val pttIdCode: Int,
        val name: String,
        val slot: Int = -1,
        val digital: String? = null,
        /** Null in lists saved before 0.6.0, which did not record modes. */
        val mode: String? = null,
        val options: Map<String, String> = emptyMap(),
    )

    private fun StagedChannel.toDto() = StagedDto(
        key, origin.name, note, channel.rxFreqHz, channel.duplex.name, channel.offsetHz,
        squelchToString(channel.txSquelch), squelchToString(channel.rxSquelch), channel.power.name,
        channel.wide, channel.scanAdd, channel.busyLockout, channel.pttId.name, channel.pttIdCode, channel.name, slot,
        // A channel whose mode is still unknown keeps saving without one until a read settles it.
        channel.digitalMode, if (modeUnknown) null else channel.mode.name, channel.options,
    )

    private fun StagedDto.toDomain() = StagedChannel(
        key = key,
        channel = Channel(
            rxFreqHz = rx, duplex = Duplex.valueOf(duplex), offsetHz = offset,
            txSquelch = squelchFromString(tx), rxSquelch = squelchFromString(rxTone), power = Power.valueOf(power),
            wide = wide, scanAdd = scan, busyLockout = bcl, pttId = PttId.valueOf(pttId), pttIdCode = pttIdCode, name = name,
            digitalMode = digital,
            mode = mode?.let { runCatching { Modulation.valueOf(it) }.getOrNull() } ?: Modulation.FM,
            options = options,
        ),
        origin = runCatching { ChannelOrigin.valueOf(origin) }.getOrDefault(ChannelOrigin.MANUAL),
        note = note,
        slot = slot,
        modeUnknown = mode == null,
    )

    private fun squelchToString(s: Squelch) = SquelchText.format(s)
    private fun squelchFromString(s: String) = SquelchText.parse(s)
}
