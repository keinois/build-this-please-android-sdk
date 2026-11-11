package io.buildthisplease.compose

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.NotificationPage
import io.buildthisplease.core.RequestSnapshot
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
@LooperMode(LooperMode.Mode.PAUSED)
class FeedbackNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun clearViewModels() {
        compose.runOnIdle { store.clear() }
    }

    @Test fun inlineCreateEntrySupersedesRestorationEvenWhenDismissed() {
        val startup = show { client -> BuildThisPleaseFeedback(client, stateKey = STATE_KEY) }
        compose.onNodeWithContentDescription("New request").performClick()
        compose.onNodeWithText("Submit request").assertIsDisplayed()
        finishLists(startup)
        compose.onNodeWithContentDescription("Cancel").performClick()

        compose.onNodeWithText("Feature requests").assertIsDisplayed()
        assertSelectionCleared(startup)
    }

    @Test fun hostCreateCallbackSupersedesRestorationBeforeHostNavigation() {
        var openedCreate = false
        val startup = show { client ->
            BuildThisPleaseFeedbackList(
                client,
                onOpenTicket = {},
                onCreateRequest = { openedCreate = true },
                stateKey = STATE_KEY,
            )
        }
        compose.onNodeWithContentDescription("New request").performClick()
        assertTrue(openedCreate)
        finishLists(startup)

        assertSelectionCleared(startup)
    }

    @Test fun inlineDelegatedOpenSupersedesRestorationBeforeHostNavigation() {
        var openedTicket: String? = null
        val startup = show { client ->
            BuildThisPleaseFeedback(client, onOpenRequest = { openedTicket = it }, stateKey = STATE_KEY)
        }
        compose.onNodeWithText("request").performClick()
        assertEquals("request", openedTicket)
        finishLists(startup)

        assertSelectionCleared(startup)
    }

    @Test fun boardDelegatedOpenSupersedesRestorationBeforeHostNavigation() {
        var openedTicket: String? = null
        val startup = show { client ->
            BuildThisPleaseFeedbackList(
                client,
                onOpenTicket = { openedTicket = it },
                onCreateRequest = {},
                stateKey = STATE_KEY,
            )
        }
        compose.onNodeWithText("request").performClick()
        assertEquals("request", openedTicket)
        finishLists(startup)

        assertSelectionCleared(startup)
    }

    @Test fun directHostCreateDestinationSupersedesRestorationOnEntry() {
        val startup = show { client ->
            BuildThisPleaseCreateRequest(client, onBack = {}, onCreated = {}, stateKey = STATE_KEY)
        }
        compose.onNodeWithText("Submit request").assertIsDisplayed()
        finishLists(startup)

        compose.onNodeWithText("Submit request").assertIsDisplayed()
        assertSelectionCleared(startup)
    }

    @Test fun openingUpdatesSupersedesRestorationWhileListsFinish() {
        val startup = show { client -> BuildThisPleaseFeedback(client, stateKey = STATE_KEY) }
        compose.onNodeWithText("Updates").performClick()
        compose.onNodeWithText("No updates yet").assertIsDisplayed()
        finishLists(startup)

        compose.onNodeWithText("No updates yet").assertIsDisplayed()
        assertSelectionCleared(startup)
    }

    @Test fun backFromLoadingHostDetailInvalidatesPendingNavigationAndRestoration() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        val startup = show { client ->
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BuildThisPleaseTicketDetail(client, "host-detail", onBack = {}, stateKey = STATE_KEY)
        }
        compose.waitUntil(5_000) { startup.client.detailStarted.get() }
        assertNull(startup.model.state.value.selectedTicket)
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.runOnIdle { startup.client.detail.complete(navigationTicket("host-detail")) }
        finishLists(startup)

        assertSelectionCleared(startup)
        assertEquals(listOf("host-detail"), startup.client.ticketCalls)
    }

    private fun show(content: @Composable (BuildThisPleaseClientProtocol) -> Unit): Startup {
        val startup = Startup()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        compose.runOnIdle {
            startup.model = FeedbackViewModel(startup.client, startup.handle)
            store.put("BuildThisPlease:${startup.client.identityScopeKey}:$STATE_KEY", startup.model)
        }
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content(startup.client) }
        }
        compose.waitUntil(5_000) { startup.client.mineStarted.get() }
        return startup
    }

    private fun finishLists(startup: Startup) {
        compose.runOnIdle {
            startup.client.mine.complete(emptyList())
            startup.client.implemented.complete(emptyList())
        }
        compose.waitForIdle()
    }

    private fun assertSelectionCleared(startup: Startup) {
        assertNull(startup.model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertTrue("saved ticket was fetched after newer navigation", "saved-ticket" !in startup.client.ticketCalls)
    }

    private class Startup {
        val client = PendingNavigationClient()
        val handle = SavedStateHandle(mapOf("selectedTicketId" to "saved-ticket"))
        lateinit var model: FeedbackViewModel
    }

    private class PendingNavigationClient : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
        val mineStarted = AtomicBoolean(false)
        val detailStarted = AtomicBoolean(false)
        val mine = CompletableDeferred<List<Ticket>>()
        val implemented = CompletableDeferred<List<Ticket>>()
        val detail = CompletableDeferred<Ticket>()
        val ticketCalls = mutableListOf<String>()

        override suspend fun cachedRequests(): RequestSnapshot? = null
        override suspend fun requests() = listOf(navigationTicket("request"))
        override suspend fun myRequests(): List<Ticket> { mineStarted.set(true); return mine.await() }
        override suspend fun implementedRequests(): List<Ticket> = implemented.await()
        override suspend fun notifications(cursor: String?) = NotificationPage()
        override suspend fun ticket(id: String): Ticket {
            ticketCalls += id
            if (id == "host-detail") { detailStarted.set(true); return detail.await() }
            return navigationTicket(id)
        }
        override suspend fun comments(ticketId: String) = emptyList<Comment>()
    }

    private companion object {
        const val STATE_KEY = "navigation-race"
    }
}

private fun navigationTicket(id: String) = Ticket(
    id = id,
    title = id,
    description = "Description",
    status = TicketStatus.IN_REVIEW,
    createdAt = "2026-01-01T00:00:00.000Z",
    updatedAt = "2026-01-01T00:00:00.000Z",
    voteCount = 1,
)
