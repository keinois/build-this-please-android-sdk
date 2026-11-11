package io.buildthisplease.compose

import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.CachedRequestList
import io.buildthisplease.core.Comment
import io.buildthisplease.core.RequestSnapshot
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FeedbackSection { REQUESTS, MINE, IMPLEMENTED }

data class FeedbackUiState(
    val section: FeedbackSection = FeedbackSection.REQUESTS,
    val requests: List<Ticket> = emptyList(),
    val mine: List<Ticket> = emptyList(),
    val implemented: List<Ticket> = emptyList(),
    val selectedTicket: Ticket? = null,
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = false,
    val isActing: Boolean = false,
    val votingTicketIds: Set<String> = emptySet(),
    val error: String? = null,
    val loadedSections: Set<FeedbackSection> = emptySet(),
    val loadingSections: Set<FeedbackSection> = emptySet(),
) {
    val isCurrentSectionInitialLoading: Boolean
        get() = section in loadingSections && section !in loadedSections && currentTickets.isEmpty()
    val isCurrentSectionRefreshing: Boolean
        get() = section in loadingSections && !isCurrentSectionInitialLoading

    val currentTickets: List<Ticket> get() = when (section) {
        FeedbackSection.REQUESTS -> requests
        FeedbackSection.MINE -> mine
        FeedbackSection.IMPLEMENTED -> implemented
    }
}

class FeedbackController internal constructor(
    private val client: BuildThisPleaseClientProtocol,
    initialSection: FeedbackSection = FeedbackSection.REQUESTS,
) {
    private val mutableState = MutableStateFlow(FeedbackUiState(section = initialSection))
    private var detailGeneration = 0L
    private var isLoadingLists = false
    private var isReadingSnapshot = false
    private val activeListSections = mutableSetOf<FeedbackSection>()
    private val listRevisions = FeedbackSection.entries.associateWith { 0L }.toMutableMap()
    private var isLoadingDetail = false
    private var ticketRevision = 0L
    private val ticketUpdates = mutableMapOf<String, TicketUpdate>()
    val state: StateFlow<FeedbackUiState> = mutableState.asStateFlow()

    suspend fun load() {
        if (isLoadingLists) return
        isLoadingLists = true
        isReadingSnapshot = true
        mutableState.update { it.copy(error = null) }
        updateLoading()
        try {
            val revision = ticketRevision
            val initialListRevisions = listRevisions.toMap()
            val snapshot = try {
                client.cachedRequests()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
            // An explicit refresh may finish while the disk snapshot is being read.
            val refreshedSections = FeedbackSection.entries.filterTo(mutableSetOf()) {
                listRevisions[it] != initialListRevisions[it]
            }
            FeedbackSection.entries.filterNot { it in refreshedSections }.forEach { section ->
                snapshot?.list(section)?.let { applyList(section, it.items, revision) }
            }
            isReadingSnapshot = false
            updateLoading()
            coroutineScope {
                FeedbackSection.entries.filter { it !in refreshedSections && snapshot?.list(it)?.isFresh() != true }.forEach { section ->
                    launch { fetchSection(section) }
                }
            }
        } finally {
            isReadingSnapshot = false
            isLoadingLists = false
            updateLoading()
        }
    }

    fun selectSection(section: FeedbackSection) = mutableState.update { it.copy(section = section) }
    fun clearError() = mutableState.update { it.copy(error = null) }

    suspend fun refreshCurrentSection() {
        val section = state.value.section
        if (section in activeListSections) return
        mutableState.update { it.copy(error = null) }
        fetchSection(section)
    }

    private suspend fun fetchSection(section: FeedbackSection) {
        if (!activeListSections.add(section)) return
        val revision = ticketRevision
        updateLoading()
        try {
            applyList(section, fetch(section), revision)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(error = failure.displayMessage()) }
        } finally {
            activeListSections.remove(section)
            updateLoading()
        }
    }

    private suspend fun fetch(section: FeedbackSection): List<Ticket> = when (section) {
        FeedbackSection.REQUESTS -> client.requests()
        FeedbackSection.MINE -> client.myRequests()
        FeedbackSection.IMPLEMENTED -> client.implementedRequests()
    }

    private fun applyList(section: FeedbackSection, tickets: List<Ticket>, revision: Long) {
        // A list response may have been produced before a later vote, detail, or create completed.
        val updates = ticketUpdates.values.filter { it.revision > revision }
        val merged = mergeUpdates(section, tickets, updates)
        listRevisions[section] = requireNotNull(listRevisions[section]) + 1
        mutableState.update {
            val updated = when (section) {
                FeedbackSection.REQUESTS -> it.copy(requests = merged.sortedForRequests())
                FeedbackSection.MINE -> it.copy(mine = merged)
                FeedbackSection.IMPLEMENTED -> it.copy(implemented = merged.sortedForImplemented())
            }
            updated.copy(loadedSections = updated.loadedSections + section)
        }
    }

    private fun updateLoading() = mutableState.update {
        val snapshotSections = if (isReadingSnapshot) FeedbackSection.entries.toSet() - it.loadedSections else emptySet()
        it.copy(
            isLoading = isLoadingLists || activeListSections.isNotEmpty() || isLoadingDetail,
            loadingSections = activeListSections.toSet() + snapshotSections,
        )
    }

    suspend fun create(title: String, description: String, email: String?, idempotencyKey: String, emailUpdates: Boolean = false): Boolean {
        var emailError: String? = null
        val success = act {
            val revision = ticketRevision
            val created = client.createTicket(title, description, email, idempotencyKey)
            val ticket = ticketUpdates.values.filter { it.matches(created.id) && it.revision > revision }
                .maxByOrNull(TicketUpdate::revision)?.ticket ?: created
            replace(ticket, requestedId = created.id, created = true)
            mutableState.update { it.copy(section = FeedbackSection.MINE) }
            if (emailUpdates && !email.isNullOrBlank()) {
                try { client.updateNotificationPreferences(email = email, emailEnabled = true) }
                catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; emailError = "Your request was saved. Open Updates to retry email setup." }
            }
        }
        emailError?.let { warning -> mutableState.update { it.copy(error = warning) } }
        return success
    }

    suspend fun open(ticket: Ticket) {
        open(ticket.id, ticket)
    }

    suspend fun open(ticketId: String, initialTicket: Ticket? = null) {
        val generation = ++detailGeneration
        val revision = ticketRevision
        val ticket = initialTicket ?: state.value.allTickets().firstOrNull { it.id == ticketId }
        isLoadingDetail = true
        mutableState.update { it.copy(selectedTicket = ticket, comments = emptyList(), isLoading = true) }
        try {
            val (refreshed, comments) = coroutineScope {
                val refreshed = async { client.ticket(ticketId) }
                val comments = async { client.comments(ticketId) }
                refreshed.await() to comments.await()
            }
            if (detailGeneration != generation) return
            val updated = ticketUpdates.values.filter {
                it.matches(refreshed.id) &&
                    (it.revision > revision || refreshed.id in it.replacedIds)
            }.maxByOrNull(TicketUpdate::revision)?.ticket ?: refreshed
            replace(updated, requestedId = ticketId)
            mutableState.update { it.copy(selectedTicket = updated, comments = comments, error = null) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (detailGeneration == generation) mutableState.update { it.copy(error = failure.displayMessage()) }
        } finally {
            if (detailGeneration == generation) {
                isLoadingDetail = false
                updateLoading()
            }
        }
    }

    fun closeTicket() {
        detailGeneration++
        isLoadingDetail = false
        mutableState.update { it.copy(selectedTicket = null, comments = emptyList(), isLoading = isLoadingLists || activeListSections.isNotEmpty(), error = null) }
    }

    suspend fun toggleVote(ticket: Ticket) {
        if (!ticket.canVote || ticket.voteLocked || ticket.id in state.value.votingTicketIds) return
        mutableState.update { it.copy(votingTicketIds = it.votingTicketIds + ticket.id) }
        runCatching { client.setVote(ticket.id, ticket.hasVoted != true) }
            .onSuccess { updated -> replace(updated, requestedId = ticket.id); mutableState.update { it.copy(error = null) } }
            .onFailure { failure -> mutableState.update { it.copy(error = failure.displayMessage()) } }
        mutableState.update { it.copy(votingTicketIds = it.votingTicketIds - ticket.id) }
    }

    suspend fun reply(body: String, idempotencyKey: String): Boolean = act {
        val ticket = requireNotNull(state.value.selectedTicket)
        val comment = client.createComment(ticket.id, body, idempotencyKey)
        mutableState.update { it.copy(comments = it.comments + comment) }
    }

    suspend fun edit(comment: Comment, body: String): Boolean = act {
        val ticket = requireNotNull(state.value.selectedTicket)
        val updated = client.updateComment(ticket.id, comment.id, body)
        mutableState.update { state -> state.copy(comments = state.comments.map { if (it.id == updated.id) updated else it }) }
    }

    private suspend fun act(block: suspend () -> Unit): Boolean {
        if (state.value.isActing) return false
        mutableState.update { it.copy(isActing = true) }
        return runCatching { block() }
            .onSuccess { mutableState.update { it.copy(isActing = false, error = null) } }
            .onFailure { failure -> mutableState.update { it.copy(isActing = false, error = failure.displayMessage()) } }
            .isSuccess
    }

    private fun replace(ticket: Ticket, requestedId: String? = null, created: Boolean = false) {
        val related = ticketUpdates.values.filter { it.matches(ticket.id) || requestedId?.let(it::matches) == true }
        val effective = related.maxByOrNull(TicketUpdate::revision)?.takeIf { ticket.id in it.replacedIds }?.ticket ?: ticket
        val ids = related.flatMapTo(mutableSetOf()) { it.replacedIds + it.ticket.id }.apply {
            add(ticket.id)
            requestedId?.let(::add)
        }
        val sections = FeedbackSection.entries.filterTo(mutableSetOf()) { section ->
            when (section) {
                FeedbackSection.REQUESTS -> state.value.requests
                FeedbackSection.MINE -> state.value.mine
                FeedbackSection.IMPLEMENTED -> state.value.implemented
            }.any { it.id in ids }
        }
        if (created) sections += FeedbackSection.MINE
        val update = TicketUpdate(effective, ++ticketRevision, sections, ids - effective.id)
        related.forEach { ticketUpdates.remove(it.ticket.id) }
        ticketUpdates[effective.id] = update
        mutableState.update { state ->
            state.copy(
                requests = mergeUpdates(FeedbackSection.REQUESTS, state.requests, listOf(update)).sortedForRequests(),
                mine = mergeUpdates(FeedbackSection.MINE, state.mine, listOf(update)),
                implemented = mergeUpdates(FeedbackSection.IMPLEMENTED, state.implemented, listOf(update)).sortedForImplemented(),
                selectedTicket = state.selectedTicket?.let { if (it.id in ids) effective else it },
            )
        }
    }

    private fun mergeUpdates(section: FeedbackSection, tickets: List<Ticket>, updates: List<TicketUpdate>): List<Ticket> {
        var result = tickets
        for (update in updates.sortedBy(TicketUpdate::revision)) {
            val ticket = update.ticket
            val index = result.indexOfFirst { update.matches(it.id) }
            val belongs = when (section) {
                FeedbackSection.REQUESTS -> ticket.status in publicStatuses
                FeedbackSection.MINE -> index >= 0 || section in update.sections
                FeedbackSection.IMPLEMENTED -> ticket.status == TicketStatus.IMPLEMENTED
            }
            val remaining = result.filterNot { update.matches(it.id) }
            result = if (!belongs) remaining
            else remaining.toMutableList().apply { add(if (index < 0) 0 else index.coerceAtMost(size), ticket) }
        }
        return result
    }

    private data class TicketUpdate(val ticket: Ticket, val revision: Long, val sections: Set<FeedbackSection>, val replacedIds: Set<String> = emptySet()) {
        fun matches(id: String) = ticket.id == id || id in replacedIds
    }
}

private fun RequestSnapshot.list(section: FeedbackSection): CachedRequestList? = when (section) {
    FeedbackSection.REQUESTS -> requests
    FeedbackSection.MINE -> mine
    FeedbackSection.IMPLEMENTED -> implemented
}

private fun FeedbackUiState.allTickets(): List<Ticket> = requests + mine + implemented

private val publicStatuses = setOf(TicketStatus.IN_REVIEW, TicketStatus.PLANNED, TicketStatus.IN_PROGRESS)

private fun List<Ticket>.sortedForRequests() = sortedWith(
    compareByDescending<Ticket> { it.voteCount ?: 0 }
        .thenByDescending { when (it.status) { TicketStatus.IN_PROGRESS -> 3; TicketStatus.PLANNED -> 2; TicketStatus.IN_REVIEW -> 1; else -> 0 } }
        .thenByDescending(Ticket::createdAt)
        .thenByDescending(Ticket::id),
)

private fun List<Ticket>.sortedForImplemented() = sortedWith(
    compareByDescending<Ticket> { it.implementedAt ?: it.updatedAt }
        .thenByDescending(Ticket::id),
)

internal val Ticket.canVote: Boolean
    get() = voteCount != null && status in publicStatuses

private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank) ?: "Something went wrong. Please try again."
