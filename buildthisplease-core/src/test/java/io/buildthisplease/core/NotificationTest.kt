package io.buildthisplease.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class NotificationTest {
    @Test fun `authors start with one permanent vote while follows can be muted`() = runTest {
        val client = MockBuildThisPleaseClient()
        val request = client.createTicket("Feature", "Please build it")
        assertEquals(1, request.voteCount)
        assertTrue(request.voteLocked)
        try { client.setVote(request.id, false); fail("Author vote was removed") }
        catch (failure: BuildThisPleaseException.Server) { assertEquals("author_vote_permanent", failure.code) }
        client.setFollowing(request.id, false)
        assertFalse(client.following(request.id))
    }
    @Test fun `marking an update read persists independently of email`() = runTest {
        val client = MockBuildThisPleaseClient()
        val before = client.notifications()
        assertEquals(1, before.unreadCount)
        client.markNotificationsRead(listOf(before.items.first().id))
        assertEquals(0, client.notifications().unreadCount)
        assertFalse(client.notificationPreferences().emailEnabled)
    }
    @Test fun `completion snapshot decodes without vote information`() {
        val payload = """{"items":[{"id":"n1","ticketId":"t1","title":"Ready","createdAt":"2026-10-15T00:00:00.000Z","appVersion":"2.0","readAt":null}],"unreadCount":1,"nextCursor":null}"""
        val page = buildThisPleaseJson.decodeFromString<NotificationPage>(payload)
        assertEquals("2.0", page.items.first().appVersion)
        assertNull(page.items.first().readAt)
    }
    @Test fun `voting follows a new request but respects an explicit mute`() = runTest {
        val client = MockBuildThisPleaseClient()
        assertFalse(client.following("review"))
        client.setVote("review", true)
        assertTrue(client.following("review"))
        client.setFollowing("review", false)
        client.setVote("review", false)
        client.setVote("review", true)
        assertFalse(client.following("review"))
    }

    @Test fun `existing positional configuration calls retain their registration identity`() {
        val configuration = BuildThisPleaseConfiguration(
            "https://example.com", "btp_pk_test", BuildThisPleaseConfiguration.Environment.PRODUCTION,
            SubscriptionStatus.PRO, null, null, "com.acme.app", "1.2.3", "Android 14",
        )
        assertEquals("com.acme.app", configuration.packageName)
        assertEquals("1.2.3", configuration.appVersion)
        assertEquals("Android 14", configuration.osVersion)
        assertNull(configuration.identityNamespace)
    }

}
