package io.buildthisplease.core

interface BuildThisPleaseClientProtocol {
    /** Opaque project/account scope for retained UI state. Custom clients must change it on account switches. */
    val identityScopeKey: String get() = "${javaClass.name}:${System.identityHashCode(this)}"
    /** Local-only snapshot. Custom clients may opt out by returning null. */
    suspend fun cachedRequests(): RequestSnapshot? = null
    /** Call from a host-owned startup/foreground scope using the retained account client. */
    suspend fun preloadRequests() { preloadRequestLists(cachedRequests()) }
    /** Clear before disposing this client's account scope; in-flight reads cannot restore the cache. */
    suspend fun clearRequestCache() {}
    suspend fun configuration(): ProjectConfiguration
    suspend fun requests(): List<Ticket>
    suspend fun myRequests(): List<Ticket>
    suspend fun implementedRequests(): List<Ticket>
    suspend fun ticket(id: String): Ticket
    suspend fun comments(ticketId: String): List<Comment>
    suspend fun createTicket(title: String, description: String, email: String? = null, idempotencyKey: String = newIdempotencyKey()): Ticket
    suspend fun setVote(ticketId: String, voted: Boolean): Ticket
    suspend fun createComment(ticketId: String, body: String, idempotencyKey: String = newIdempotencyKey()): Comment
    suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment
    suspend fun updateSubscriptionStatus(status: SubscriptionStatus)
    suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String? = null)
}

fun newIdempotencyKey(): String = java.util.UUID.randomUUID().toString()
