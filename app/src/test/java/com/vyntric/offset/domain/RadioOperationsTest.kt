package com.vyntric.offset.domain

import com.vyntric.offset.AppFixtures
import com.vyntric.offset.MemoryLibrary
import com.vyntric.offset.SimLink
import com.vyntric.offset.fixedClock
import com.vyntric.offset.protocol.Channel
import com.vyntric.offset.protocol.SimulatedRadio
import com.vyntric.offset.protocol.Sleeper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioOperationsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val library = MemoryLibrary()
    private val radio = SimulatedRadio.fromImage(AppFixtures.factoryImage())
    private val link = SimLink(radio)
    private val session = RadioSession()
    private val ops = RadioOperations(
        scope,
        ReadRadio(link, library, session, fixedClock, Sleeper.NONE),
        WriteRadio(link, library, session, fixedClock, Sleeper.NONE),
    )

    @Test fun `an unexpected failure still ends the operation and frees the radio`() = scope.runTest {
        library.failHasFactory = true
        assertTrue(ops.read())
        assertFalse("only one operation at a time", ops.read())
        advanceUntilIdle()
        assertEquals(RadioOperations.State.Idle, ops.state.value)
        assertTrue(ops.result.first() is RadioOperations.Result.ReadFailed)
        library.failHasFactory = false
        assertTrue("a new operation can start", ops.read())
        advanceUntilIdle()
        assertTrue(ops.result.value is RadioOperations.Result.ReadDone)
    }

    @Test fun `a write always produces an outcome`() = scope.runTest {
        val image = AppFixtures.factoryImage()
        assertTrue(ops.write(WriteRadio.Request(WriteRadio.Content.Channels(listOf(Channel(146_520_000))), "x", image.identHex)))
        advanceUntilIdle()
        val done = ops.result.value as RadioOperations.Result.WriteDone
        assertTrue(done.outcome is WriteRadio.Outcome.Verified)
        assertEquals(RadioOperations.State.Idle, ops.state.value)
    }
}
