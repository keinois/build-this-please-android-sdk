package io.buildthisplease.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class MockBuildThisPleaseScenario { NORMAL, EMPTY, LOADING, OFFLINE, RATE_LIMITED, EXPIRED_SESSION, SERVER_ERROR }

class MockBuildThisPleaseClient(
    initialTickets: List<Ticket> = sampleTickets(),
    initialComments: Map<String, List<Comment>> = sampleComments(),
    private val scenario: MockBuildThisPleaseScenario = MockBuildThisPleaseScenario.NORMAL,
    initialSubscriptionStatus: SubscriptionStatus = SubscriptionStatus.TRIAL,
) : BuildThisPleaseClientProtocol {
    private val mutex = Mutex()
    private val tickets = (if (scenario == MockBuildThisPleaseScenario.EMPTY) emptyList() else initialTickets).toMutableList()
    private val comments = (if (scenario == MockBuildThisPleaseScenario.EMPTY) emptyMap() else initialComments).mapValues { it.value.toMutableList() }.toMutableMap()
    private val mine = tickets.map(Ticket::id).toMutableSet()
    private var subscriptionStatus = initialSubscriptionStatus
    private var requestSnapshot: RequestSnapshot? = null
    private var cacheGeneration = 0L

    override suspend fun cachedRequests(): RequestSnapshot? = mutex.withLock { requestSnapshot }
    override suspend fun clearRequestCache() { mutex.withLock { cacheGeneration++; requestSnapshot = null } }

    private suspend fun requestList(section: RequestList, source: () -> List<Ticket>): List<Ticket> {
        val generation = mutex.withLock { cacheGeneration }
        prepare()
        return mutex.withLock {
            source().also { items ->
                if (generation == cacheGeneration) {
                    val list = CachedRequestList(items, System.currentTimeMillis())
                    val snapshot = requestSnapshot ?: RequestSnapshot()
                    requestSnapshot = when (section) {
                        RequestList.REQUESTS -> snapshot.copy(requests = list)
                        RequestList.MINE -> snapshot.copy(mine = list)
                        RequestList.IMPLEMENTED -> snapshot.copy(implemented = list)
                    }
                }
            }
        }
    }

    private fun cacheTicket(ticket: Ticket, created: Boolean = false) {
        fun List<Ticket>.replace() = map { if (it.id == ticket.id) ticket else it }
        requestSnapshot = (requestSnapshot ?: if (created) RequestSnapshot() else null)?.let { value ->
            value.copy(
                requests = value.requests?.let { it.copy(items = it.items.replace()) },
                mine = value.mine?.let { it.copy(items = if (created) listOf(ticket) + it.items.filterNot { it.id == ticket.id } else it.items.replace()) }
                    ?: if (created) CachedRequestList(listOf(ticket), System.currentTimeMillis(), isComplete = false) else null,
                implemented = value.implemented?.let { it.copy(items = it.items.replace()) },
            )
        }
    }

    private var preferences = NotificationPreferences()
    private var updates = listOf(CompletionNotification("completion-implemented", "implemented", "Implemented release notes", "Requests you follow now have completion updates.", "1.0", "2026-10-15T00:00:00.000Z"))
    private val muted = mutableSetOf<String>()
    private val followed = tickets.filter { it.hasVoted == true }.mapTo(mutableSetOf(), Ticket::id)
    override suspend fun notifications(cursor: String?): NotificationPage {
        prepare(); val items = mutex.withLock {
            if (scenario == MockBuildThisPleaseScenario.EMPTY) emptyList() else updates.map { it.copy(following = it.ticketId in followed) }
        }
        return NotificationPage(items, items.count { it.readAt == null })
    }
    override suspend fun markNotificationsRead(ids: List<String>) { prepare(); updates = updates.map { if (it.id in ids) it.copy(readAt = "2026-10-15T00:00:00.000Z") else it } }
    override suspend fun notificationPreferences(): NotificationPreferences { prepare(); return preferences }
    override suspend fun updateNotificationPreferences(email: String?, emailEnabled: Boolean?, localNotificationsEnabled: Boolean?): NotificationPreferences {
        prepare(); preferences = preferences.copy(email = email ?: preferences.email, emailEnabled = emailEnabled ?: preferences.emailEnabled, emailVerified = emailEnabled ?: preferences.emailVerified, localNotificationsEnabled = localNotificationsEnabled ?: preferences.localNotificationsEnabled)
        return preferences
    }
    override suspend fun following(ticketId: String): Boolean { prepare(); return mutex.withLock { ticketId in followed } }
    override suspend fun setFollowing(ticketId: String, following: Boolean) {
        prepare()
        mutex.withLock {
            if (following) { muted.remove(ticketId); followed.add(ticketId) }
            else { muted.add(ticketId); followed.remove(ticketId) }
        }
    }

    override suspend fun configuration(): ProjectConfiguration {
        prepare()
        return ProjectConfiguration(
            ProjectConfiguration.Project("mock", "BuildThisPlease", "mock", "#5B5CE2"),
            ProjectConfiguration.Capabilities(true, true),
        )
    }
    override suspend fun requests(): List<Ticket> = requestList(RequestList.REQUESTS) { tickets.filter { it.status in publicStatuses }.toList() }
    override suspend fun myRequests(): List<Ticket> = requestList(RequestList.MINE) {
            tickets.filter { it.id in mine }
                .map { value -> value.canonicalTicketId?.let { destination -> tickets.firstOrNull { it.id == destination } } ?: value }
                .distinctBy(Ticket::id)
    }
    override suspend fun implementedRequests(): List<Ticket> = requestList(RequestList.IMPLEMENTED) { tickets.filter { it.status == TicketStatus.IMPLEMENTED }.toList() }
    override suspend fun ticket(id: String): Ticket { prepare(); return mutex.withLock { tickets.first { it.id == id }.let { value -> value.canonicalTicketId?.let { destination -> tickets.firstOrNull { it.id == destination } } ?: value }.also { cacheTicket(it) } } }
    override suspend fun comments(ticketId: String): List<Comment> { prepare(); return mutex.withLock { comments[ticketId]?.toList().orEmpty() } }
    override suspend fun createTicket(title: String, description: String, email: String?, idempotencyKey: String): Ticket {
        prepare()
        return mutex.withLock {
        val now = nowIso()
        Ticket(UUID.randomUUID().toString(), title.trim(), description.trim(), TicketStatus.PENDING, createdAt = now, updatedAt = now, voteCount = 1, hasVoted = true, voteLocked = true).also {
            tickets.add(0, it)
            mine += it.id
            followed += it.id
            cacheTicket(it, created = true)
        }
        }
    }
    override suspend fun setVote(ticketId: String, voted: Boolean): Ticket {
        prepare()
        return mutex.withLock {
        val index = tickets.indexOfFirst { it.id == ticketId }
        val current = tickets[index]
        if (current.voteLocked) throw BuildThisPleaseException.Server("author_vote_permanent", "The author’s first vote is permanent.", 409)
        val updated = current.copy(
            hasVoted = voted,
            voteCount = (current.voteCount ?: 0) + if (voted && current.hasVoted != true) 1 else if (!voted && current.hasVoted == true) -1 else 0,
        )
        tickets[index] = updated
        if (voted && ticketId !in muted) followed.add(ticketId)
        cacheTicket(updated)
        updated
        }
    }
    override suspend fun createComment(ticketId: String, body: String, idempotencyKey: String): Comment {
        prepare()
        return mutex.withLock {
        val now = nowIso()
        Comment(
            id = UUID.randomUUID().toString(),
            ticketId = ticketId,
            author = Comment.Author.USER,
            body = body.trim(),
            isEdited = false,
            isHidden = false,
            isApproved = false,
            isMine = true,
            createdAt = now,
            updatedAt = now,
        ).also {
            comments.getOrPut(ticketId) { mutableListOf() } += it
        }
        }
    }
    override suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment {
        prepare()
        return mutex.withLock {
        val list = comments.getOrPut(ticketId) { mutableListOf() }
        val index = list.indexOfFirst { it.id == commentId && it.isMine && !it.isHidden }
        if (index < 0) throw BuildThisPleaseException.Server("comment_not_editable", "This message cannot be edited.", 403)
        list[index].copy(body = body.trim(), isEdited = true, updatedAt = nowIso()).also { list[index] = it }
        }
    }
    override suspend fun updateSubscriptionStatus(status: SubscriptionStatus) { prepare(); mutex.withLock { subscriptionStatus = status } }
    override suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String?) { prepare() }

    private suspend fun prepare() {
        when (scenario) {
            MockBuildThisPleaseScenario.NORMAL, MockBuildThisPleaseScenario.EMPTY -> Unit
            MockBuildThisPleaseScenario.LOADING -> delay(2_000)
            MockBuildThisPleaseScenario.OFFLINE -> throw BuildThisPleaseException.Offline()
            MockBuildThisPleaseScenario.RATE_LIMITED -> throw BuildThisPleaseException.Server("rate_limit_exceeded", "Too many requests. Try again shortly.", 429)
            MockBuildThisPleaseScenario.EXPIRED_SESSION -> throw BuildThisPleaseException.Server("installation_session_invalid", "The feedback session expired. Please try again.", 401)
            MockBuildThisPleaseScenario.SERVER_ERROR -> throw BuildThisPleaseException.Server("fixture_error", "The example server is unavailable.", 503)
        }
    }

    companion object {
        private val publicStatuses = setOf(TicketStatus.IN_REVIEW, TicketStatus.PLANNED, TicketStatus.IN_PROGRESS)
        fun sampleTickets(): List<Ticket> {
            val now = nowIso()
            return listOf(
                Ticket("review", "Filter requests by status", "Focus on the requests that are planned or already being built.", TicketStatus.IN_REVIEW, createdAt = now, updatedAt = now, voteCount = 12, hasVoted = false),
                Ticket("planned", "Remember my selected tab", "Open on the same section I used last time.", TicketStatus.PLANNED, createdAt = now, updatedAt = now, voteCount = 7, hasVoted = true),
                Ticket("pending", "Compact weekly summary", "A quick overview of what changed this week.", TicketStatus.PENDING, createdAt = now, updatedAt = now),
                Ticket("progress", "Export my requests", "Keep a copy of feedback submitted from this installation.", TicketStatus.IN_PROGRESS, createdAt = now, updatedAt = now, voteCount = 4, hasVoted = false),
                Ticket("rejected", "Public profile", "A profile is intentionally unnecessary for anonymous feedback.", TicketStatus.REJECTED, commentsLocked = true, createdAt = now, updatedAt = now),
                Ticket("merged", "Duplicate selected-tab request", "This request resolves to the planned canonical ticket.", TicketStatus.MERGED, commentsLocked = true, createdAt = now, updatedAt = now, canonicalTicketId = "planned"),
                Ticket("implemented", "Implemented release notes", "A quiet list of shipped requests.", TicketStatus.IMPLEMENTED, commentsLocked = true, createdAt = now, updatedAt = now, implementedAt = now, implementedAppVersion = "1.0", implementationNote = "Implemented requests now have a dedicated list."),
            )
        }

        fun sampleComments(): Map<String, List<Comment>> {
            val now = nowIso()
            return mapOf(
                "review" to listOf(
                    Comment("comment-user", "review", Comment.Author.USER, "A filter for planned work would help.", true, false, true, true, now, now),
                    Comment("comment-admin", "review", Comment.Author.ADMINISTRATOR, "That is now part of the design.", false, false, true, false, now, now),
                    Comment("comment-other", "review", Comment.Author.USER, "I would use this too.", false, false, true, false, now, now),
                    Comment("comment-hidden", "review", Comment.Author.USER, null, false, true, true, true, now, now),
                ),
            )
        }
    }
}

private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())
