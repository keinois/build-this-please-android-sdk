package io.buildthisplease.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.CompletionNotification
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.NotificationPage
import io.buildthisplease.core.NotificationPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHeldInitialBadgeReadCannotRestoreUnreadAfterReadingAnUpdate() {
        assertHeldBadgeReadCannotRestoreUnread(holdResumeRead = false)
    }

    @Test fun aHeldResumeBadgeReadCannotRestoreUnreadAfterReadingAnUpdate() {
        assertHeldBadgeReadCannotRestoreUnread(holdResumeRead = true)
    }

    @Test fun switchingAccountScopeWhileResumedRefreshesTheNewBadge() {
        val fetches = AtomicInteger()
        val oldReadReturned = AtomicBoolean(false)
        val releaseOldRead = CompletableDeferred<Unit>()
        val firstAccount = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override val identityScopeKey: String = "first-account"
            override suspend fun notifications(cursor: String?): NotificationPage {
                fetches.incrementAndGet()
                withContext(NonCancellable) { releaseOldRead.await() }
                oldReadReturned.set(true)
                return NotificationPage(unreadCount = 7)
            }
        }
        val secondAccount = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override val identityScopeKey: String = "second-account"
            override suspend fun notifications(cursor: String?): NotificationPage {
                fetches.incrementAndGet()
                return NotificationPage(unreadCount = 2)
            }
        }
        val client = mutableStateOf<BuildThisPleaseClientProtocol>(firstAccount)
        compose.setContent { BuildThisPleaseFeedback(client.value, stateKey = "account-badge") }
        compose.waitUntil(5_000) { fetches.get() == 1 }
        compose.runOnIdle { client.value = secondAccount }
        compose.waitForIdle()
        compose.waitUntil(5_000) { fetches.get() >= 2 }
        compose.onNodeWithText("Updates (2)").assertIsDisplayed()
        compose.runOnIdle { releaseOldRead.complete(Unit) }
        compose.waitUntil(5_000) { oldReadReturned.get() }
        compose.waitForIdle()
        compose.onNodeWithText("Updates (2)").assertIsDisplayed()
        compose.onNodeWithText("Updates (7)").assertDoesNotExist()
    }

    private fun assertHeldBadgeReadCannotRestoreUnread(holdResumeRead: Boolean) {
        val fetches = AtomicInteger()
        val read = AtomicBoolean(false)
        val oldReadReturned = AtomicBoolean(false)
        val releaseOldRead = CompletableDeferred<Unit>()
        val openedTicket = AtomicReference<String>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val heldRead = if (holdResumeRead) 2 else 1
        val client = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override suspend fun notifications(cursor: String?): NotificationPage {
                if (fetches.incrementAndGet() == heldRead) {
                    // A custom transport may finish a response despite cancellation.
                    withContext(NonCancellable) { releaseOldRead.await() }
                    oldReadReturned.set(true)
                    return NotificationPage(unreadCount = 4)
                }
                return NotificationPage(
                    items = listOf(CompletionNotification("new", "review", "Newly shipped", createdAt = "2026-10-15T00:00:00Z", readAt = if (read.get()) "read" else null)),
                    unreadCount = if (read.get()) 0 else 1,
                )
            }
            override suspend fun markNotificationsRead(ids: List<String>) { read.set(true) }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BuildThisPleaseFeedback(client, stateKey = "held-badge", onOpenRequest = { openedTicket.set(it) })
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { fetches.get() == 1 }
        if (holdResumeRead) {
            compose.onNodeWithText("Updates (1)").assertIsDisplayed()
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitUntil(5_000) { fetches.get() == heldRead }
        }
        // Unread loading never holds the request list behind it.
        compose.onNodeWithText("Filter requests by status").assertIsDisplayed()
        compose.onNodeWithText(if (holdResumeRead) "Updates (1)" else "Updates").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Newly shipped").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Newly shipped").performClick()
        compose.waitUntil(5_000) { openedTicket.get() == "review" && fetches.get() == heldRead + 2 }
        compose.onNodeWithText("Updates").assertIsDisplayed()
        compose.onNodeWithText("Updates (1)").assertDoesNotExist()

        compose.runOnIdle { releaseOldRead.complete(Unit) }
        compose.waitUntil(5_000) { oldReadReturned.get() }
        compose.waitForIdle()
        compose.onNodeWithText("Updates").assertIsDisplayed()
        compose.onNodeWithText("Updates (4)").assertDoesNotExist()
        assertTrue(read.get())
    }

    @Test fun returningFromRefreshedInboxUpdatesTheBoardBadge() {
        val completed = AtomicBoolean(false)
        val fetches = AtomicInteger()
        val client = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override suspend fun notifications(cursor: String?): NotificationPage {
                fetches.incrementAndGet()
                return if (completed.get()) NotificationPage(listOf(CompletionNotification("new", "implemented", "Newly shipped", createdAt = "2026-10-15T00:00:00Z")), 1) else NotificationPage()
            }
        }
        compose.setContent { BuildThisPleaseFeedback(client = client, stateKey = "inbox-refresh") }
        compose.onNodeWithText("Updates").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No updates yet").fetchSemanticsNodes().isNotEmpty() }
        completed.set(true)
        val before = fetches.get()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Refresh updates"))
        compose.onNodeWithText("Refresh updates").performClick()
        compose.waitUntil(5_000) { fetches.get() > before }
        compose.onNodeWithText("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Updates (1)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Updates (1)").assertIsDisplayed()
    }

    @Test fun successfulVoteRefreshesFollowStateAndAllowsMuting() {
        val client = MockBuildThisPleaseClient()
        val voted = mutableStateOf(false)
        compose.setContent { MaterialTheme { RequestFollowButton(client, "review", voted.value) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Follow request").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Follow request").assertIsEnabled()
        runBlocking { client.setVote("review", true) }
        compose.runOnIdle { voted.value = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stop updates").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Stop updates").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Follow request").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(runBlocking { client.following("review") })
    }

    @Test fun aFailedFollowReadCanBeRetriedWithoutLeavingTheRequest() {
        val reads = AtomicInteger()
        val client = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override suspend fun following(ticketId: String): Boolean {
                if (reads.incrementAndGet() == 1) error("Offline")
                return false
            }
        }
        compose.setContent { MaterialTheme { RequestFollowButton(client, "review", false) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        compose.onNodeWithText("Follow request").assertIsEnabled()
        assertEquals(2, reads.get())
    }

    @Test fun aVoteRefreshCannotOverwriteASuccessfulMute() {
        val followed = AtomicBoolean(true)
        val reads = AtomicInteger()
        val completedReads = AtomicInteger()
        val writes = AtomicInteger()
        val completedWrites = AtomicInteger()
        val releaseMute = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val client = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
            override suspend fun following(ticketId: String): Boolean {
                val snapshot = followed.get()
                if (reads.incrementAndGet() > 1) releaseRefresh.await()
                completedReads.incrementAndGet()
                return snapshot
            }
            override suspend fun setFollowing(ticketId: String, following: Boolean) {
                writes.incrementAndGet()
                releaseMute.await()
                followed.set(following)
                completedWrites.incrementAndGet()
            }
        }
        val voted = mutableStateOf(false)
        compose.setContent { MaterialTheme { RequestFollowButton(client, "review", voted.value) } }
        compose.waitUntil(5_000) { completedReads.get() == 1 }
        compose.onNodeWithText("Stop updates").performClick()
        compose.waitUntil(5_000) { writes.get() == 1 }
        compose.runOnIdle { voted.value = true }
        compose.waitForIdle()
        // Replacing a queued refresh cancels its coroutine without ending the mute.
        compose.runOnIdle { voted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { releaseMute.complete(Unit) }
        compose.waitUntil(5_000) { completedWrites.get() == 1 && reads.get() == 2 }
        compose.onNodeWithText("Follow request").assertIsNotEnabled()
        compose.runOnIdle { releaseRefresh.complete(Unit) }
        compose.waitUntil(5_000) { completedReads.get() == 2 }
        compose.waitForIdle()
        compose.onNodeWithText("Follow request").assertIsEnabled()
        assertFalse(followed.get())
    }

    @Test fun aDelayedEmailSaveCannotOverwriteTheNextLocalPreferenceSave() {
        val client = DelayedPreferencesClient()
        compose.setContent { MaterialTheme { BuildThisPleaseUpdates(client, onBack = {}, onOpenTicket = {}) } }
        compose.waitUntil(5_000) { client.reads.get() > 0 }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Stop completion emails"))
        compose.onNodeWithText("Stop completion emails").performClick()
        compose.waitUntil(5_000) { client.writes.get() == 1 }
        compose.onNode(hasScrollAction()).performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).assertIsNotEnabled()
        assertEquals(1, client.writes.get())
        compose.runOnIdle { client.releaseEmail.complete(Unit) }
        compose.waitUntil(5_000) { client.completed.get() == 1 }
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { client.completed.get() == 2 }
        compose.onNode(isToggleable()).assertIsOn()
        assertFalse(client.server.get().emailEnabled)
        assertEquals(true, client.server.get().localNotificationsEnabled)
    }

    @Test fun activationRefreshWaitsForThePendingPreferenceSave() {
        val client = DelayedPreferencesClient()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { BuildThisPleaseUpdates(client, onBack = {}, onOpenTicket = {}) }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { client.reads.get() > 0 }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Stop completion emails"))
        compose.onNodeWithText("Stop completion emails").performClick()
        compose.waitUntil(5_000) { client.writes.get() == 1 }
        val before = client.reads.get()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(before, client.reads.get())
        compose.runOnIdle { client.releaseEmail.complete(Unit) }
        compose.waitUntil(5_000) { client.completed.get() == 1 && client.reads.get() > before }
        compose.onNode(hasScrollAction()).performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOff()
        assertFalse(client.server.get().emailEnabled)
    }

    private class DelayedPreferencesClient : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
        val server = AtomicReference(NotificationPreferences(email = "person@example.com", emailEnabled = true, emailVerified = true))
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val completed = AtomicInteger()
        val releaseEmail = CompletableDeferred<Unit>()
        override suspend fun notificationPreferences(): NotificationPreferences {
            reads.incrementAndGet()
            return server.get()
        }
        override suspend fun updateNotificationPreferences(email: String?, emailEnabled: Boolean?, localNotificationsEnabled: Boolean?): NotificationPreferences {
            writes.incrementAndGet()
            val snapshot = server.updateAndGet { current -> current.copy(email = email ?: current.email, emailEnabled = emailEnabled ?: current.emailEnabled, localNotificationsEnabled = localNotificationsEnabled ?: current.localNotificationsEnabled) }
            if (emailEnabled == false) releaseEmail.await()
            completed.incrementAndGet()
            return snapshot
        }
    }
}
