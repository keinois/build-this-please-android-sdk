package io.buildthisplease.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionStatus {
    @SerialName("unknown") UNKNOWN,
    @SerialName("free") FREE,
    @SerialName("trial") TRIAL,
    @SerialName("pro") PRO,
    @SerialName("expired") EXPIRED,
}

@Serializable
enum class TicketStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_review") IN_REVIEW,
    @SerialName("planned") PLANNED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("implemented") IMPLEMENTED,
    @SerialName("rejected") REJECTED,
    @SerialName("merged") MERGED,
    @SerialName("archived") ARCHIVED,
}

@Serializable
data class Ticket(
    val id: String,
    val title: String,
    val description: String,
    val status: TicketStatus,
    val commentsLocked: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val implementedAt: String? = null,
    val implementedAppVersion: String? = null,
    val implementationNote: String? = null,
    val canonicalTicketId: String? = null,
    val voteCount: Int? = null,
    val hasVoted: Boolean? = null,
    val voteLocked: Boolean = false,
)

@Serializable
data class Comment(
    val id: String,
    val ticketId: String,
    val author: Author,
    val body: String? = null,
    val isEdited: Boolean,
    val isHidden: Boolean,
    val isApproved: Boolean = true,
    val isMine: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
) {
    @Serializable
    enum class Author {
        @SerialName("user") USER,
        @SerialName("administrator") ADMINISTRATOR,
    }
}

@Serializable data class Page<T>(val items: List<T>, val nextCursor: String? = null)
@Serializable data class TicketEnvelope(val ticket: Ticket)
@Serializable data class CommentEnvelope(val comment: Comment)

@Serializable
data class ProjectConfiguration(
    val project: Project,
    val capabilities: Capabilities,
) {
    @Serializable data class Project(val id: String, val name: String, val slug: String, val accentColor: String)
    @Serializable data class Capabilities(val allowInProgressVoting: Boolean, val implementedCommentsLocked: Boolean)
}

sealed class BuildThisPleaseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidConfiguration(message: String) : BuildThisPleaseException(message)
    class Server(val code: String, message: String, val status: Int) : BuildThisPleaseException(message)
    class InvalidResponse(cause: Throwable? = null) : BuildThisPleaseException("The service returned an invalid response.", cause)
    class IntegrityUnavailable(cause: Throwable? = null) : BuildThisPleaseException("This device cannot establish a secure feedback session.", cause)
    class Offline(cause: Throwable? = null) : BuildThisPleaseException("You appear to be offline.", cause)
}
