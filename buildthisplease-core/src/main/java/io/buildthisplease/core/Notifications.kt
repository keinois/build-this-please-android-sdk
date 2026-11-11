package io.buildthisplease.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable data class CompletionNotification(
    val id: String, val ticketId: String, val title: String,
    val implementationNote: String? = null, val appVersion: String? = null,
    val createdAt: String, val readAt: String? = null,
    /** Current follow state, or null when the synchronizer must query the client. */
    val following: Boolean? = null,
)
@Serializable data class NotificationPage(val items: List<CompletionNotification> = emptyList(), val unreadCount: Int = 0, val nextCursor: String? = null)
@Serializable data class NotificationPreferences(
    val email: String? = null, val emailEnabled: Boolean = false, val emailVerified: Boolean = false,
    val verificationPending: Boolean = false, val localNotificationsEnabled: Boolean = false, val identityLinked: Boolean = false,
)

/** Retain one synchronizer per project and signed-in host account. The host schedules
 * background work and requests POST_NOTIFICATIONS in context. */
class BuildThisPleaseNotificationSync(context: Context, private val client: BuildThisPleaseClientProtocol, namespace: String) {
    private val context = context.applicationContext
    private val storage = this.context.getSharedPreferences("btp.notifications." + MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    suspend fun synchronize(inBackground: Boolean = false): Int = mutex.withLock {
        var page = client.notifications()
        val unreadCount = page.unreadCount
        if (!inBackground || !client.notificationPreferences().localNotificationsEnabled) return@withLock unreadCount
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return@withLock unreadCount
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.btp_completion_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!canDeliver(manager)) return@withLock unreadCount
        val delivered = storage.getStringSet("delivered", emptySet())!!.toMutableSet()
        val seenCursors = mutableSetOf<String>()
        while (true) {
            for (update in page.items.filter { it.readAt == null && it.ticketId !in delivered }) {
                currentCoroutineContext().ensureActive()
                if (!(update.following ?: client.following(update.ticketId))) continue
                currentCoroutineContext().ensureActive()
                if (!canDeliver(manager)) return@withLock unreadCount
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    putExtra("buildThisPleaseTicketId", update.ticketId)
                    putExtra("buildThisPleaseNotificationId", update.id)
                    data = android.net.Uri.parse("btp-notification://open/" + android.net.Uri.encode(update.id))
                }
                val pending = launch?.let { android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE) }
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.btp_notification)
                    .setContentTitle(context.getString(R.string.btp_completion_title))
                    .setContentText(update.title).setAutoCancel(true).setContentIntent(pending).build()
                try { manager.notify(update.id, 0, notification) } catch (_: SecurityException) { return@withLock unreadCount }
                delivered.add(update.ticketId)
                storage.edit().putStringSet("delivered", delivered.toSet()).apply()
            }
            val cursor = page.nextCursor ?: break
            if (!seenCursors.add(cursor)) throw BuildThisPleaseException.InvalidResponse()
            page = client.notifications(cursor)
        }
        unreadCount
    }
    private fun canDeliver(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    companion object { const val CHANNEL_ID = "buildthisplease.completions" }
}
