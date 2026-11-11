package io.buildthisplease.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationSyncTest {
    @Test
    @Config(sdk = [26, 28])
    fun `a blocked channel preserves unread alerts for delivery after re-enabling`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(BuildThisPleaseNotificationSync.CHANNEL_ID, "Completed requests", NotificationManager.IMPORTANCE_NONE))
        val client = pagedClient(1, mutableListOf())
        val namespace = UUID.randomUUID().toString()

        assertEquals(1, BuildThisPleaseNotificationSync(context, client, namespace).synchronize(inBackground = true))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        manager.cancelAll()

        // Simulate the user changing the system channel settings. Robolectric
        // exposes its stored channel, whereas apps cannot increase importance.
        manager.getNotificationChannel(BuildThisPleaseNotificationSync.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_DEFAULT
        assertEquals(1, BuildThisPleaseNotificationSync(context, client, namespace).synchronize(inBackground = true))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun `channel deletion during a follow lookup does not consume delivery`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        var deleteChannel = true
        val client = object : BuildThisPleaseClientProtocol by pagedClient(1, mutableListOf(), includeFollowState = false) {
            override suspend fun following(ticketId: String): Boolean {
                if (deleteChannel) {
                    manager.deleteNotificationChannel(BuildThisPleaseNotificationSync.CHANNEL_ID)
                    deleteChannel = false
                }
                return true
            }
        }
        val sync = BuildThisPleaseNotificationSync(context, client, UUID.randomUUID().toString())

        assertEquals(1, sync.synchronize(inBackground = true))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(1, sync.synchronize(inBackground = true))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    @Config(sdk = [24, 28])
    fun `background sync visits every page and persists deduplication across instances`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val cursors = mutableListOf<String?>()
        val client = pagedClient(1005, cursors)
        val namespace = UUID.randomUUID().toString()
        val sync = BuildThisPleaseNotificationSync(context, client, namespace)
        assertEquals(1005, sync.synchronize(inBackground = true))
        assertEquals(11, cursors.size)
        assertEquals(1005, shadowOf(manager).allNotifications.size)
        manager.cancelAll()
        val restored = BuildThisPleaseNotificationSync(context, client, namespace)
        assertEquals(1005, restored.synchronize(inBackground = true))
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun `foreground sync fetches the count without posting local alerts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val cursors = mutableListOf<String?>()
        val sync = BuildThisPleaseNotificationSync(context, pagedClient(201, cursors), UUID.randomUUID().toString())
        assertEquals(201, sync.synchronize())
        assertEquals(listOf<String?>(null), cursors)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun `muting after completion skips alerts without removing unread history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val muted = mutableSetOf("ticket-0")
        val client = pagedClient(2, mutableListOf(), muted)
        val sync = BuildThisPleaseNotificationSync(context, client, UUID.randomUUID().toString())
        assertEquals(2, sync.synchronize(inBackground = true))
        assertEquals(listOf("Shipped 1"), shadowOf(manager).allNotifications.map { it.extras.getString("android.text") })
        val history = client.notifications()
        assertEquals(2, history.items.size)
        org.junit.Assert.assertTrue(history.items.all { it.readAt == null })
        muted.clear()
        assertEquals(2, sync.synchronize(inBackground = true))
        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test fun `responses without follow state recheck before local delivery`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val checks = mutableListOf<String>()
        val client = pagedClient(1, mutableListOf(), mutableSetOf("ticket-0"), false, checks)
        val sync = BuildThisPleaseNotificationSync(context, client, UUID.randomUUID().toString())
        assertEquals(1, sync.synchronize(inBackground = true))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(listOf("ticket-0"), checks)
    }

    private fun pagedClient(count: Int, cursors: MutableList<String?>, muted: MutableSet<String> = mutableSetOf(), includeFollowState: Boolean = true, followChecks: MutableList<String> = mutableListOf()) = object : BuildThisPleaseClientProtocol by MockBuildThisPleaseClient() {
        override suspend fun notifications(cursor: String?): NotificationPage {
            cursors.add(cursor)
            val start = cursor?.toInt() ?: 0
            val end = minOf(start + 100, count)
            return NotificationPage((start until end).map { CompletionNotification("notice-$it", "ticket-$it", "Shipped $it", createdAt = "2026-10-15T00:00:00.000Z", following = if (includeFollowState) "ticket-$it" !in muted else null) }, count, if (end < count) end.toString() else null)
        }
        override suspend fun notificationPreferences() = NotificationPreferences(localNotificationsEnabled = true)
        override suspend fun following(ticketId: String): Boolean {
            followChecks.add(ticketId)
            return ticketId !in muted
        }
    }
}
