package io.buildthisplease.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import io.buildthisplease.core.Ticket
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class FeedbackViewModel(
    val client: BuildThisPleaseClientProtocol,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val restoredTicketId = savedStateHandle.get<String>(SELECTED_TICKET_KEY)
    private var ticketNavigationRevision = 0L
    private val controller = FeedbackController(
        client = client,
        initialSection = savedStateHandle.get<String>(SECTION_KEY)
            ?.let { runCatching { FeedbackSection.valueOf(it) }.getOrNull() }
            ?: FeedbackSection.REQUESTS,
    )
    val state: StateFlow<FeedbackUiState> = controller.state

    init {
        viewModelScope.launch {
            state.collectLatest { value ->
                savedStateHandle[SECTION_KEY] = value.section.name
            }
        }
        viewModelScope.launch {
            controller.load()
            if (ticketNavigationRevision == 0L) {
                restoredTicketId?.let { controller.open(it) }
            }
        }
    }

    fun selectSection(section: FeedbackSection) {
        closeTicket()
        controller.selectSection(section)
    }
    fun clearError() = controller.clearError()
    fun closeTicket() {
        ticketNavigationRevision++
        savedStateHandle[SELECTED_TICKET_KEY] = null
        controller.closeTicket()
    }
    fun refreshIfStale() = launch { controller.load() }
    fun refreshCurrentSection() = launch { controller.refreshCurrentSection() }
    fun refreshTicket(ticket: Ticket) = launchTicketNavigation { controller.open(ticket) }
    fun open(ticket: Ticket) = launchTicketNavigation {
        savedStateHandle[SELECTED_TICKET_KEY] = ticket.id
        controller.open(ticket)
    }
    fun open(ticketId: String) = launchTicketNavigation {
        savedStateHandle[SELECTED_TICKET_KEY] = ticketId
        controller.open(ticketId)
    }
    fun toggleVote(ticket: Ticket) = launch { controller.toggleVote(ticket) }

    fun create(
        title: String,
        description: String,
        email: String?,
        idempotencyKey: String,
        onResult: (Boolean) -> Unit,
    ): Job {
        closeTicket()
        return launch { onResult(controller.create(title, description, email, idempotencyKey)) }
    }

    fun reply(body: String, idempotencyKey: String, onResult: (Boolean) -> Unit) =
        launch { onResult(controller.reply(body, idempotencyKey)) }

    fun edit(comment: Comment, body: String, onResult: (Boolean) -> Unit) =
        launch { onResult(controller.edit(comment, body)) }

    private fun launchTicketNavigation(block: suspend () -> Unit): Job {
        // Claim navigation before dispatch, including calls made before startup runs.
        val revision = ++ticketNavigationRevision
        return launch { if (ticketNavigationRevision == revision) block() }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    private companion object {
        const val SECTION_KEY = "section"
        const val SELECTED_TICKET_KEY = "selectedTicketId"
    }
}
