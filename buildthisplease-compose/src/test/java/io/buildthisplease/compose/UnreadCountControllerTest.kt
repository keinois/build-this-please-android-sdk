package io.buildthisplease.compose

import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.NotificationPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnreadCountControllerTest {
    @Test fun `an inbox close zero supersedes a held activation read`() = runTest {
        val oldResponse = CompletableDeferred<Int>()
        val client = QueuedUnreadClient(
            Read(oldResponse, ignoresCancellation = true),
            Read(CompletableDeferred(0)),
        )
        val controller = UnreadCountController(client, backgroundScope)
        controller.refresh()
        runCurrent()

        controller.invalidate() // Open Updates while activation is still reading.
        controller.refresh() // Close after reading the update.
        runCurrent()
        assertEquals(0, controller.count.value)
        assertEquals(2, client.calls)

        oldResponse.complete(4)
        runCurrent()
        assertEquals(2, client.completions)
        assertEquals(0, controller.count.value)
    }

    @Test fun `a superseded failure preserves the latest successful count`() = runTest {
        val oldResponse = CompletableDeferred<Int>()
        val client = QueuedUnreadClient(
            Read(oldResponse, ignoresCancellation = true),
            Read(CompletableDeferred(2)),
        )
        val controller = UnreadCountController(client, backgroundScope)
        controller.refresh()
        runCurrent()
        controller.refresh()
        runCurrent()
        assertEquals(2, controller.count.value)

        oldResponse.completeExceptionally(IllegalStateException("Older read failed"))
        runCurrent()
        assertEquals(2, controller.count.value)
    }

    @Test fun `a failed refresh keeps the known count and a later zero clears it`() = runTest {
        val failure = CompletableDeferred<Int>().apply { completeExceptionally(IllegalStateException("Offline")) }
        val client = QueuedUnreadClient(
            Read(CompletableDeferred(3)), Read(failure), Read(CompletableDeferred(0)),
        )
        val controller = UnreadCountController(client, backgroundScope)
        controller.refresh()
        runCurrent()
        assertEquals(3, controller.count.value)
        controller.refresh()
        runCurrent()
        assertEquals(3, controller.count.value)
        controller.refresh()
        runCurrent()
        assertEquals(0, controller.count.value)
    }

    @Test fun `invalidation cancels its read without clearing the known count`() = runTest {
        val heldResponse = CompletableDeferred<Int>()
        val heldRead = Read(heldResponse)
        val client = QueuedUnreadClient(Read(CompletableDeferred(3)), heldRead)
        val controller = UnreadCountController(client, backgroundScope)
        controller.refresh()
        runCurrent()
        controller.refresh()
        runCurrent()
        controller.invalidate()
        runCurrent()

        assertTrue(heldRead.cancelled)
        assertEquals(3, controller.count.value)
        heldResponse.complete(9)
        runCurrent()
        assertEquals(3, controller.count.value)
    }

    @Test fun `disposal invalidates a cancellation ignoring read before the board returns`() = runTest {
        val oldResponse = CompletableDeferred<Int>()
        val client = QueuedUnreadClient(
            Read(oldResponse, ignoresCancellation = true), Read(CompletableDeferred(1)),
        )
        val departedBoard = UnreadCountController(client, backgroundScope)
        departedBoard.refresh()
        runCurrent()
        departedBoard.invalidate()
        val returnedBoard = UnreadCountController(client, backgroundScope)
        returnedBoard.refresh()
        runCurrent()
        oldResponse.complete(7)
        runCurrent()

        assertEquals(0, departedBoard.count.value)
        assertEquals(1, returnedBoard.count.value)
    }

    @Test fun `cancelling the owning scope prevents a late publication`() = runTest {
        val response = CompletableDeferred<Int>()
        val client = QueuedUnreadClient(Read(response, ignoresCancellation = true))
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val controller = UnreadCountController(client, scope)
        controller.refresh()
        runCurrent()
        scope.cancel()
        response.complete(5)
        runCurrent()

        assertEquals(1, client.completions)
        assertEquals(0, controller.count.value)
    }

    private class Read(val response: CompletableDeferred<Int>, val ignoresCancellation: Boolean = false) {
        var cancelled = false
    }

    private class QueuedUnreadClient(vararg reads: Read) :
        BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
        private val reads = ArrayDeque(reads.toList())
        var calls = 0
        var completions = 0

        override suspend fun notifications(cursor: String?): NotificationPage {
            calls++
            val read = reads.removeFirst()
            try {
                val count = if (read.ignoresCancellation) {
                    withContext(NonCancellable) { read.response.await() }
                } else read.response.await()
                completions++
                return NotificationPage(unreadCount = count)
            } catch (failure: CancellationException) {
                read.cancelled = true
                throw failure
            }
        }
    }
}
