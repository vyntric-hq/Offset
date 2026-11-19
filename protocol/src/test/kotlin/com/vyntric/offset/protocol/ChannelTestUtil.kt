package com.vyntric.offset.protocol

/** This channel without the radio specific options a decode fills in, to compare with a plain [Channel]. */
internal fun Channel?.plain(): Channel? = this?.copy(options = emptyMap())
