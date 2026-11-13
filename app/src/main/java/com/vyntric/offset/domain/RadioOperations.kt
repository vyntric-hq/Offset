/*
 * Offset, a UV-5R programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.domain

import com.vyntric.offset.protocol.CloneProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs radio reads and writes in the application scope, one at a time, so leaving a screen or
 * rotating the phone never cancels a clone halfway. Screens observe [state] and [result].
 */
class RadioOperations(
    private val scope: CoroutineScope,
    private val readRadio: ReadRadio,
    private val writeRadio: WriteRadio,
) {
    enum class Kind { READ, WRITE }

    sealed interface State {
        data object Idle : State
        data class Running(val kind: Kind, val progress: CloneProgress?) : State
    }

    sealed interface Result {
        data class ReadDone(val read: ReadRadio.Result) : Result
        data class ReadFailed(val message: String) : Result
        data class WriteDone(val outcome: WriteRadio.Outcome, val request: WriteRadio.Request) : Result
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    val busy: Boolean get() = _state.value is State.Running

    /** Starts a read. Returns false when another operation is running. */
    fun read(): Boolean = start(Kind.READ) {
        try {
            Result.ReadDone(readRadio { p -> _state.value = State.Running(Kind.READ, p) })
        } catch (e: Exception) {
            Result.ReadFailed(WriteRadio.describe(e))
        }
    }

    /** Starts a write. Returns false when another operation is running. */
    fun write(request: WriteRadio.Request): Boolean = start(Kind.WRITE) {
        val outcome = try {
            writeRadio(request) { p -> _state.value = State.Running(Kind.WRITE, p) }
        } catch (e: Exception) {
            // WriteRadio reports every expected failure itself; this is the last line of defence.
            WriteRadio.Outcome.Failed(
                WriteRadio.Stage.WRITE,
                "Unexpected error: ${e.message ?: e.javaClass.simpleName}. Read the radio to check it; " +
                    "the backup from before the write is in Images.",
                backupImageId = null,
                radioMayBePartial = true,
            )
        }
        Result.WriteDone(outcome, request)
    }

    fun consume(result: Result) {
        _result.compareAndSet(result, null)
    }

    @Synchronized
    private fun start(kind: Kind, work: suspend () -> Result): Boolean {
        if (_state.value is State.Running) return false
        _state.value = State.Running(kind, null)
        _result.value = null
        scope.launch {
            try {
                // A clone must run to completion or fail on its own terms; never cancel it midway.
                _result.value = withContext(NonCancellable) { work() }
            } finally {
                _state.value = State.Idle
            }
        }
        return true
    }
}
