/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.RadioImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The most recent image read from the connected radio, shared by the screens. */
class RadioSession {
    data class Snapshot(val image: RadioImage, val readAtMillis: Long)

    private val _last = MutableStateFlow<Snapshot?>(null)
    val last: StateFlow<Snapshot?> = _last.asStateFlow()

    fun update(image: RadioImage, atMillis: Long) {
        _last.value = Snapshot(image, atMillis)
    }

    /** Forget the radio, for example after the cable is unplugged. */
    fun clear() {
        _last.value = null
    }
}
