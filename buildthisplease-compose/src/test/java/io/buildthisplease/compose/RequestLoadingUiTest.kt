package io.buildthisplease.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.CachedRequestList
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.RequestSnapshot
import io.buildthisplease.core.Ticket
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
@LooperMode(LooperMode.Mode.PAUSED)
class RequestLoadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedEmptyRequestsAppearWhileMineIsStillLoading() {
        val client = EmptyBoardClient(useCache = true)
        compose.setContent { BuildThisPleaseFeedback(client, stateKey = "cached-empty") }
        compose.waitUntil(5_000) { client.mineStarted.get() }

        compose.onNodeWithText("No public requests").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading requests…").assertDoesNotExist()
        assertEquals(0, client.requestCalls.get())
        compose.runOnIdle { client.mine.complete(emptyList()) }
        compose.waitForIdle()
        // The board's resume hook also respects the fresh empty Requests cache.
        assertEquals(0, client.requestCalls.get())
    }

    @Test fun fastEmptyRequestsAppearWhileMineIsStillLoading() {
        val client = EmptyBoardClient(useCache = false)
        compose.setContent { BuildThisPleaseFeedback(client, stateKey = "fast-empty") }
        compose.waitUntil(5_000) { client.mineStarted.get() }

        compose.onNodeWithText("No public requests").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading requests…").assertDoesNotExist()
        assertEquals(1, client.requestCalls.get())
        compose.runOnIdle { client.mine.complete(emptyList()) }
    }

    @Test fun cachedEmptyRequestsRemainVisibleWhenMineFails() {
        val client = EmptyBoardClient(useCache = true)
        compose.setContent { BuildThisPleaseFeedback(client, stateKey = "cached-empty-offline") }
        compose.waitUntil(5_000) { client.mineStarted.get() }
        compose.runOnIdle { client.mine.completeExceptionally(IllegalStateException("Offline")) }

        compose.onNodeWithText("No public requests").assertIsDisplayed()
        compose.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test fun emptySectionPullRefreshForcesANetworkRequest() {
        val client = EmptyBoardClient(useCache = true)
        compose.setContent { BuildThisPleaseFeedback(client, stateKey = "cached-empty-pull") }
        compose.waitUntil(5_000) { client.mineStarted.get() }
        compose.runOnIdle { client.mine.complete(emptyList()) }
        compose.waitForIdle()
        assertEquals(0, client.requestCalls.get())

        compose.onRoot().performTouchInput {
            swipeDown(startY = height * 0.35f, endY = height * 0.9f, durationMillis = 500)
        }

        compose.waitUntil(5_000) { client.requestCalls.get() > 0 }
        assertEquals(1, client.requestCalls.get())
    }

    private class EmptyBoardClient(private val useCache: Boolean) :
        BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
        val requestCalls = AtomicInteger()
        val mineStarted = AtomicBoolean(false)
        val mine = CompletableDeferred<List<Ticket>>()

        override suspend fun cachedRequests(): RequestSnapshot? = if (useCache) {
            val empty = CachedRequestList(emptyList(), System.currentTimeMillis())
            RequestSnapshot(requests = empty, implemented = empty)
        } else null

        override suspend fun requests(): List<Ticket> {
            requestCalls.incrementAndGet()
            return emptyList()
        }

        override suspend fun myRequests(): List<Ticket> {
            mineStarted.set(true)
            return mine.await()
        }

        override suspend fun implementedRequests(): List<Ticket> = emptyList()
    }
}
