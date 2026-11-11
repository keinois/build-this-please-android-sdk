package io.buildthisplease.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.CachedRequestList
import io.buildthisplease.core.Comment
import io.buildthisplease.core.ProjectConfiguration
import io.buildthisplease.core.RequestSnapshot
import io.buildthisplease.core.SubscriptionStatus
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackControllerTest {
    @Test
    fun `stale cached sections appear before network replies`() = runTest {
        val client = ControllableClient().apply {
            snapshot = cachedSnapshot(updatedAt = 0)
            nextRequests = CompletableDeferred()
            nextMine = CompletableDeferred()
            nextImplemented = CompletableDeferred()
        }
        val requests = requireNotNull(client.nextRequests)
        val mine = requireNotNull(client.nextMine)
        val implemented = requireNotNull(client.nextImplemented)
        val controller = FeedbackController(client)

        val load = async { controller.load() }
        runCurrent()

        assertEquals(listOf("cached-request"), controller.state.value.requests.map(Ticket::id))
        assertEquals(listOf("cached-mine"), controller.state.value.mine.map(Ticket::id))
        assertEquals(listOf("cached-done"), controller.state.value.implemented.map(Ticket::id))
        assertTrue(controller.state.value.isLoading)
        requests.complete(emptyList())
        mine.complete(emptyList())
        implemented.complete(emptyList())
        load.await()
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun `cached empty current section is loaded while another section is slow`() = runTest {
        val mine = CompletableDeferred<List<Ticket>>()
        val client = ControllableClient().apply {
            snapshot = RequestSnapshot(
                requests = CachedRequestList(emptyList(), System.currentTimeMillis()),
                implemented = CachedRequestList(emptyList(), System.currentTimeMillis()),
            )
            nextMine = mine
        }
        val controller = FeedbackController(client)
        val load = async { controller.load() }
        runCurrent()

        assertTrue(controller.state.value.isLoading)
        assertEquals(setOf(FeedbackSection.MINE), controller.state.value.loadingSections)
        assertTrue(FeedbackSection.REQUESTS in controller.state.value.loadedSections)
        assertFalse(controller.state.value.isCurrentSectionInitialLoading)
        assertFalse(controller.state.value.isCurrentSectionRefreshing)
        controller.selectSection(FeedbackSection.MINE)
        assertTrue(controller.state.value.isCurrentSectionInitialLoading)
        mine.complete(emptyList())
        load.await()
        assertFalse(controller.state.value.isCurrentSectionInitialLoading)
    }

    @Test
    fun `fast empty current section stops loading while another network section is slow`() = runTest {
        val mine = CompletableDeferred<List<Ticket>>()
        val client = ControllableClient().apply {
            nextRequests = CompletableDeferred<List<Ticket>>().apply { complete(emptyList()) }
            nextMine = mine
        }
        val controller = FeedbackController(client)
        val load = async { controller.load() }
        runCurrent()

        assertTrue(controller.state.value.isLoading)
        assertTrue(controller.state.value.requests.isEmpty())
        assertTrue(FeedbackSection.REQUESTS in controller.state.value.loadedSections)
        assertFalse(controller.state.value.isCurrentSectionInitialLoading)
        assertFalse(controller.state.value.isCurrentSectionRefreshing)
        mine.complete(emptyList())
        load.await()
    }

    @Test
    fun `cached requests can be pulled while an unrelated mine load is pending`() = runTest {
        val mine = CompletableDeferred<List<Ticket>>()
        val client = ControllableClient().apply {
            snapshot = RequestSnapshot(
                requests = CachedRequestList(emptyList(), System.currentTimeMillis()),
                implemented = CachedRequestList(emptyList(), System.currentTimeMillis()),
            )
            nextMine = mine
        }
        val controller = FeedbackController(client)
        val load = async { controller.load() }
        runCurrent()
        val requests = CompletableDeferred<List<Ticket>>()
        client.nextRequests = requests

        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()

        assertEquals(1, client.requestCalls)
        assertEquals(setOf(FeedbackSection.REQUESTS, FeedbackSection.MINE), controller.state.value.loadingSections)
        assertTrue(controller.state.value.isCurrentSectionRefreshing)
        requests.complete(listOf(fixtureTicket("pulled")))
        refresh.await()
        assertEquals("pulled", controller.state.value.requests.single().id)
        assertTrue(controller.state.value.isLoading)
        assertEquals(setOf(FeedbackSection.MINE), controller.state.value.loadingSections)
        assertFalse(controller.state.value.isCurrentSectionRefreshing)
        mine.complete(emptyList())
        load.await()
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun `fast requests can be pulled and remain loading after unrelated mine completes`() = runTest {
        val mine = CompletableDeferred<List<Ticket>>()
        val client = ControllableClient().apply {
            nextRequests = CompletableDeferred<List<Ticket>>().apply { complete(emptyList()) }
            nextMine = mine
        }
        val controller = FeedbackController(client)
        val load = async { controller.load() }
        runCurrent()
        val requests = CompletableDeferred<List<Ticket>>()
        client.nextRequests = requests

        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()

        assertEquals(2, client.requestCalls)
        mine.complete(emptyList())
        load.await()
        assertTrue(controller.state.value.isLoading)
        assertEquals(setOf(FeedbackSection.REQUESTS), controller.state.value.loadingSections)
        assertTrue(controller.state.value.isCurrentSectionRefreshing)
        requests.complete(listOf(fixtureTicket("pulled")))
        refresh.await()
        assertEquals("pulled", controller.state.value.requests.single().id)
        assertFalse(controller.state.value.isLoading)
        assertTrue(controller.state.value.loadingSections.isEmpty())
    }

    @Test
    fun `refreshing a known empty section uses refresh state instead of initial loading`() = runTest {
        val client = ControllableClient().apply {
            snapshot = RequestSnapshot(requests = CachedRequestList(emptyList(), System.currentTimeMillis()))
        }
        val controller = FeedbackController(client)
        controller.load()
        val requests = CompletableDeferred<List<Ticket>>()
        client.nextRequests = requests
        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()

        assertFalse(controller.state.value.isCurrentSectionInitialLoading)
        assertTrue(controller.state.value.isCurrentSectionRefreshing)
        requests.complete(emptyList())
        refresh.await()
        assertFalse(controller.state.value.isCurrentSectionRefreshing)
    }

    @Test
    fun `fresh cached sections skip opening fetch but pull refresh reaches network`() = runTest {
        val client = ControllableClient().apply { snapshot = cachedSnapshot() }
        val controller = FeedbackController(client)

        controller.load()

        assertEquals(0, client.requestCalls)
        assertEquals(0, client.mineCalls)
        assertEquals(0, client.implementedCalls)
        assertEquals("cached-request", controller.state.value.requests.single().id)
        controller.refreshCurrentSection()
        assertEquals(1, client.requestCalls)
        assertEquals("request", controller.state.value.requests.single().id)
    }

    @Test
    fun `opening fetches missing and stale sections independently`() = runTest {
        val client = ControllableClient().apply {
            snapshot = RequestSnapshot(
                requests = CachedRequestList(listOf(fixtureTicket("fresh")), System.currentTimeMillis()),
                mine = CachedRequestList(listOf(fixtureTicket("stale")), 0),
            )
        }
        val controller = FeedbackController(client)

        controller.load()

        assertEquals(0, client.requestCalls)
        assertEquals(1, client.mineCalls)
        assertEquals(1, client.implementedCalls)
        assertEquals("fresh", controller.state.value.requests.single().id)
        assertEquals("mine", controller.state.value.mine.single().id)
        assertEquals("done", controller.state.value.implemented.single().id)
    }

    @Test
    fun `successful sections appear immediately while failed sections retain cache`() = runTest {
        val mine = CompletableDeferred<List<Ticket>>()
        val implemented = CompletableDeferred<List<Ticket>>()
        val client = ControllableClient().apply {
            snapshot = cachedSnapshot(updatedAt = 0)
            nextMine = mine
            nextImplemented = implemented
        }
        val controller = FeedbackController(client)
        val load = async { controller.load() }
        runCurrent()

        assertEquals("request", controller.state.value.requests.single().id)
        assertEquals("cached-mine", controller.state.value.mine.single().id)
        assertEquals("cached-done", controller.state.value.implemented.single().id)
        mine.completeExceptionally(IllegalStateException("Offline"))
        runCurrent()
        assertEquals("cached-mine", controller.state.value.mine.single().id)
        implemented.complete(listOf(fixtureTicket("new-done", status = TicketStatus.IMPLEMENTED)))
        load.await()

        assertEquals("new-done", controller.state.value.implemented.single().id)
        assertEquals("Offline", controller.state.value.error)
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun `offline refresh retains the visible cached list`() = runTest {
        val client = ControllableClient().apply { snapshot = cachedSnapshot() }
        val controller = FeedbackController(client)
        controller.load()
        client.nextRequests = CompletableDeferred<List<Ticket>>().apply {
            completeExceptionally(IllegalStateException("Offline"))
        }

        controller.refreshCurrentSection()

        assertEquals("cached-request", controller.state.value.requests.single().id)
        assertEquals("Offline", controller.state.value.error)
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun `delayed list response preserves a newer vote`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextRequests = gate
        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()

        controller.toggleVote(controller.state.value.requests.single())
        gate.complete(listOf(fixtureTicket("request")))
        refresh.await()

        assertEquals(true, controller.state.value.requests.single().hasVoted)
        assertEquals(2, controller.state.value.requests.single().voteCount)
    }

    @Test
    fun `delayed mine response keeps a request created after fetch began`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client, FeedbackSection.MINE)
        controller.load()
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextMine = gate
        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()

        assertTrue(controller.create("New request", "Description", null, "create-key"))
        gate.complete(listOf(fixtureTicket("mine", status = TicketStatus.PENDING)))
        refresh.await()

        assertEquals(listOf("created", "mine"), controller.state.value.mine.map(Ticket::id))
    }

    @Test
    fun `delayed list response preserves a newer ticket detail`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextRequests = gate
        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()
        client.nextTicket = CompletableDeferred<Ticket>().apply {
            complete(fixtureTicket("request", title = "Updated detail"))
        }

        controller.open(controller.state.value.requests.single())
        gate.complete(listOf(fixtureTicket("request")))
        refresh.await()

        assertEquals("Updated detail", controller.state.value.requests.single().title)
        assertEquals("Updated detail", controller.state.value.selectedTicket?.title)
    }

    @Test
    fun `delayed request list cannot restore a ticket that detail moved to implemented`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextRequests = gate
        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()
        client.nextTicket = CompletableDeferred<Ticket>().apply {
            complete(fixtureTicket("request", status = TicketStatus.IMPLEMENTED))
        }

        controller.open(controller.state.value.requests.single())
        gate.complete(listOf(fixtureTicket("request")))
        refresh.await()

        assertTrue(controller.state.value.requests.isEmpty())
        assertEquals(setOf("request", "done"), controller.state.value.implemented.map(Ticket::id).toSet())
        assertEquals(TicketStatus.IMPLEMENTED, controller.state.value.selectedTicket?.status)
    }

    @Test
    fun `merged detail replaces source and canonical once across lists and delayed responses`() = runTest {
        val source = fixtureTicket("source")
        val canonical = fixtureTicket("canonical", status = TicketStatus.PLANNED)
        val destination = fixtureTicket("destination", status = TicketStatus.IMPLEMENTED)
        val client = ControllableClient().apply {
            val list = CachedRequestList(listOf(source, canonical), System.currentTimeMillis())
            snapshot = RequestSnapshot(requests = list, mine = list, implemented = CachedRequestList(emptyList(), System.currentTimeMillis()))
        }
        val controller = FeedbackController(client)
        controller.load()
        val requests = CompletableDeferred<List<Ticket>>()
        val mine = CompletableDeferred<List<Ticket>>()
        client.nextRequests = requests
        val oldRequests = async { controller.refreshCurrentSection() }
        runCurrent()
        controller.selectSection(FeedbackSection.MINE)
        client.nextMine = mine
        val oldMine = async { controller.refreshCurrentSection() }
        runCurrent()
        client.nextTicket = CompletableDeferred<Ticket>().apply { complete(canonical) }
        controller.open(source)
        assertEquals(listOf(canonical), controller.state.value.requests)
        assertEquals(listOf(canonical), controller.state.value.mine)
        assertEquals(canonical, controller.state.value.selectedTicket)
        client.nextTicket = CompletableDeferred<Ticket>().apply { complete(destination) }
        controller.open(canonical)
        requests.complete(listOf(source, canonical))
        mine.complete(emptyList())
        oldRequests.await(); oldMine.await()

        assertTrue(controller.state.value.requests.isEmpty())
        assertEquals(listOf(destination), controller.state.value.mine)
        assertEquals(listOf(destination), controller.state.value.implemented)
        assertEquals(destination, controller.state.value.selectedTicket)
        // Old identity metadata must not override a newer authoritative membership removal.
        client.nextMine = CompletableDeferred<List<Ticket>>().apply { complete(emptyList()) }
        controller.refreshCurrentSection()
        client.nextTicket = CompletableDeferred<Ticket>().apply { complete(destination) }
        controller.open(source)
        assertTrue(controller.state.value.mine.isEmpty())
    }

    @Test
    fun `held create preserves canonical detail aliases across a delayed source list`() = runTest {
        val source = fixtureTicket("source", status = TicketStatus.PENDING)
        val canonical = fixtureTicket("canonical", status = TicketStatus.PLANNED)
        val client = ControllableClient().apply {
            val list = CachedRequestList(listOf(source), System.currentTimeMillis())
            snapshot = RequestSnapshot(requests = list, mine = list, implemented = CachedRequestList(emptyList(), System.currentTimeMillis()))
        }
        val controller = FeedbackController(client)
        controller.load()
        val requests = CompletableDeferred<List<Ticket>>()
        client.nextRequests = requests
        val oldRequests = async { controller.refreshCurrentSection() }
        runCurrent()
        val created = CompletableDeferred<Ticket>()
        client.nextCreatedTicket = created
        val create = async { controller.create("New request", "Details", null, "create") }
        runCurrent()
        client.nextTicket = CompletableDeferred<Ticket>().apply { complete(canonical) }
        controller.open(source)
        created.complete(source)
        assertTrue(create.await())
        requests.complete(listOf(source))
        oldRequests.await()

        assertEquals(listOf(canonical), controller.state.value.requests)
        assertEquals(listOf(canonical), controller.state.value.mine)
        assertEquals(canonical, controller.state.value.selectedTicket)
    }

    @Test
    fun `delayed detail response preserves a newer vote`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        val gate = CompletableDeferred<Ticket>()
        client.nextTicket = gate
        val detail = async { controller.open(controller.state.value.requests.single()) }
        runCurrent()

        controller.toggleVote(requireNotNull(controller.state.value.selectedTicket))
        gate.complete(fixtureTicket("request"))
        detail.await()

        assertEquals(true, controller.state.value.selectedTicket?.hasVoted)
        assertEquals(2, controller.state.value.selectedTicket?.voteCount)
        assertEquals(true, controller.state.value.requests.single().hasVoted)
    }

    @Test
    fun `reopening the same ticket ignores an earlier detail reply`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        val gate = CompletableDeferred<Ticket>()
        client.nextTicket = gate
        val firstDetail = async { controller.open(fixtureTicket("request")) }
        runCurrent()
        controller.closeTicket()
        client.nextTicket = CompletableDeferred<Ticket>().apply {
            complete(fixtureTicket("request", title = "Newest detail"))
        }
        controller.open(fixtureTicket("request"))

        gate.complete(fixtureTicket("request", title = "Earlier detail"))
        firstDetail.await()

        assertEquals("Newest detail", controller.state.value.selectedTicket?.title)
    }

    @Test
    fun `refresh result remains attached to the section that initiated it`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        controller.selectSection(FeedbackSection.REQUESTS)
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextRequests = gate

        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()
        controller.selectSection(FeedbackSection.MINE)
        gate.complete(listOf(fixtureTicket("new-request")))
        refresh.await()

        assertEquals(listOf("new-request"), controller.state.value.requests.map(Ticket::id))
        assertEquals(listOf("mine"), controller.state.value.mine.map(Ticket::id))
    }

    @Test
    fun `closing a ticket prevents an in-flight detail request from reopening it`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        val gate = CompletableDeferred<Ticket>()
        client.nextTicket = gate

        val request = async { controller.open(fixtureTicket("request")) }
        runCurrent()
        controller.closeTicket()
        gate.complete(fixtureTicket("request", title = "Refreshed"))
        request.await()

        assertNull(controller.state.value.selectedTicket)
    }

    @Test
    fun `view model ticket open keeps newer navigation while startup lists finish`() = withSlowModelStartup { model, startup ->
        runCurrent()
        assertEquals("request", model.state.value.requests.single().id)
        assertEquals(setOf(FeedbackSection.MINE, FeedbackSection.IMPLEMENTED), model.state.value.loadingSections)

        model.open(model.state.value.requests.single())
        runCurrent()
        assertEquals("request", model.state.value.selectedTicket?.id)
        startup.completeLists()
        runCurrent()

        assertEquals("request", model.state.value.selectedTicket?.id)
        assertEquals("request", startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("request"), startup.client.ticketCalls)
    }

    @Test
    fun `view model ticket id open keeps host destination while startup lists finish`() = withSlowModelStartup { model, startup ->
        runCurrent()
        model.open("request")
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertEquals("request", model.state.value.selectedTicket?.id)
        assertEquals("request", startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("request"), startup.client.ticketCalls)
    }

    @Test
    fun `view model open then close stays closed when startup lists finish`() = withSlowModelStartup { model, startup ->
        runCurrent()
        model.open(model.state.value.requests.single())
        runCurrent()
        model.closeTicket()
        assertNull(startup.handle.get<String>("selectedTicketId"))
        startup.completeLists()
        runCurrent()

        assertNull(model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("request"), startup.client.ticketCalls)
    }

    @Test
    fun `view model detail refresh keeps newer ticket while startup lists finish`() = withSlowModelStartup { model, startup ->
        runCurrent()
        model.refreshTicket(model.state.value.requests.single())
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertEquals("request", model.state.value.selectedTicket?.id)
        assertEquals(listOf("request"), startup.client.ticketCalls)
    }

    @Test
    fun `view model restores saved ticket after slow lists without newer navigation`() = withSlowModelStartup { model, startup ->
        runCurrent()
        assertEquals("request", model.state.value.requests.single().id)
        assertNull(model.state.value.selectedTicket)
        assertTrue(startup.client.ticketCalls.isEmpty())
        startup.completeLists()
        runCurrent()

        assertEquals("saved-ticket", model.state.value.selectedTicket?.id)
        assertEquals("saved-ticket", startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("saved-ticket"), startup.client.ticketCalls)
    }

    @Test
    fun `view model foreground refresh preserves pending saved restoration`() = withSlowModelStartup { model, startup ->
        runCurrent()
        model.refreshIfStale()
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertEquals("saved-ticket", model.state.value.selectedTicket?.id)
        assertEquals("saved-ticket", startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("saved-ticket"), startup.client.ticketCalls)
    }

    @Test
    fun `view model close before startup begins cancels saved restoration`() = withSlowModelStartup { model, startup ->
        model.closeTicket()
        assertNull(startup.handle.get<String>("selectedTicketId"))
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertNull(model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertTrue(startup.client.ticketCalls.isEmpty())
    }

    @Test
    fun `view model open before startup begins claims newer navigation`() = withSlowModelStartup { model, startup ->
        model.open("request")
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertEquals("request", model.state.value.selectedTicket?.id)
        assertEquals("request", startup.handle.get<String>("selectedTicketId"))
        assertEquals(listOf("request"), startup.client.ticketCalls)
    }

    @Test
    fun `view model immediate open then close does not queue a stale navigation`() = withSlowModelStartup { model, startup ->
        model.open(fixtureTicket("request"))
        model.closeTicket()
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertNull(model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertTrue(startup.client.ticketCalls.isEmpty())
    }

    @Test
    fun `view model selecting section supersedes saved ticket restoration`() = withSlowModelStartup { model, startup ->
        runCurrent()
        model.selectSection(FeedbackSection.IMPLEMENTED)
        runCurrent()
        startup.completeLists()
        runCurrent()

        assertEquals(FeedbackSection.IMPLEMENTED, model.state.value.section)
        assertEquals(FeedbackSection.IMPLEMENTED.name, startup.handle.get<String>("section"))
        assertNull(model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertTrue(startup.client.ticketCalls.isEmpty())
    }

    @Test
    fun `view model successful create during startup cannot reveal restored ticket`() = withSlowModelStartup { model, startup ->
        runCurrent()
        val created = CompletableDeferred<Boolean>()
        model.create("New request", "Description", null, "create-during-startup") { created.complete(it) }
        runCurrent()
        assertTrue(created.await())
        startup.completeLists()
        runCurrent()

        assertEquals(FeedbackSection.MINE, model.state.value.section)
        assertEquals("created", model.state.value.mine.single().id)
        assertNull(model.state.value.selectedTicket)
        assertNull(startup.handle.get<String>("selectedTicketId"))
        assertTrue(startup.client.ticketCalls.isEmpty())
    }

    private fun withSlowModelStartup(
        block: suspend TestScope.(FeedbackViewModel, SlowModelStartup) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val startup = SlowModelStartup()
        val model = FeedbackViewModel(startup.client, startup.handle)
        try {
            block(model, startup)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `view model restores section and selected ticket from saved state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val handle = SavedStateHandle(
                mapOf("section" to FeedbackSection.MINE.name, "selectedTicketId" to "mine"),
            )
            val model = FeedbackViewModel(ControllableClient(), handle)
            advanceUntilIdle()

            assertEquals(FeedbackSection.MINE, model.state.value.section)
            assertEquals("mine", model.state.value.selectedTicket?.id)

            model.selectSection(FeedbackSection.IMPLEMENTED)
            advanceUntilIdle()
            assertEquals(FeedbackSection.IMPLEMENTED.name, handle.get<String>("section"))
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class SlowModelStartup {
    val mine = CompletableDeferred<List<Ticket>>()
    val implemented = CompletableDeferred<List<Ticket>>()
    val handle = SavedStateHandle(mapOf("selectedTicketId" to "saved-ticket"))
    val client = ControllableClient().apply {
        nextMine = mine
        nextImplemented = implemented
    }

    fun completeLists() {
        mine.complete(emptyList())
        implemented.complete(emptyList())
    }
}

private class ControllableClient : BuildThisPleaseClientProtocol {
    var snapshot: RequestSnapshot? = null
    var nextRequests: CompletableDeferred<List<Ticket>>? = null
    var nextMine: CompletableDeferred<List<Ticket>>? = null
    var nextImplemented: CompletableDeferred<List<Ticket>>? = null
    var requestCalls = 0
    var mineCalls = 0
    var implementedCalls = 0
    val ticketCalls = mutableListOf<String>()
    var nextTicket: CompletableDeferred<Ticket>? = null
    var nextCreatedTicket: CompletableDeferred<Ticket>? = null

    override suspend fun configuration() = ProjectConfiguration(
        ProjectConfiguration.Project("mock", "Mock", "mock", "#5B5CE2"),
        ProjectConfiguration.Capabilities(true, true),
    )
    override suspend fun cachedRequests(): RequestSnapshot? = snapshot
    override suspend fun requests(): List<Ticket> {
        requestCalls++
        return nextRequests?.also { nextRequests = null }?.await() ?: listOf(fixtureTicket("request"))
    }
    override suspend fun myRequests(): List<Ticket> {
        mineCalls++
        return nextMine?.also { nextMine = null }?.await() ?: listOf(fixtureTicket("mine", status = TicketStatus.PENDING))
    }
    override suspend fun implementedRequests(): List<Ticket> {
        implementedCalls++
        return nextImplemented?.also { nextImplemented = null }?.await() ?: listOf(fixtureTicket("done", status = TicketStatus.IMPLEMENTED))
    }
    override suspend fun ticket(id: String): Ticket {
        ticketCalls += id
        return nextTicket?.also { nextTicket = null }?.await() ?: fixtureTicket(id)
    }
    override suspend fun comments(ticketId: String) = emptyList<Comment>()
    override suspend fun createTicket(title: String, description: String, email: String?, idempotencyKey: String) = nextCreatedTicket?.also { nextCreatedTicket = null }?.await() ?: fixtureTicket("created", title)
    override suspend fun setVote(ticketId: String, voted: Boolean) = fixtureTicket(ticketId).copy(hasVoted = voted, voteCount = if (voted) 2 else 1)
    override suspend fun createComment(ticketId: String, body: String, idempotencyKey: String): Comment = error("Not used")
    override suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment = error("Not used")
    override suspend fun updateSubscriptionStatus(status: SubscriptionStatus) = Unit
    override suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String?) = Unit
}

private fun fixtureTicket(
    id: String,
    title: String = id,
    status: TicketStatus = TicketStatus.IN_REVIEW,
) = Ticket(
    id = id,
    title = title,
    description = "Description",
    status = status,
    createdAt = "2026-01-01T00:00:00.000Z",
    updatedAt = "2026-01-01T00:00:00.000Z",
    voteCount = if (status == TicketStatus.IN_REVIEW) 1 else null,
)

private fun cachedSnapshot(updatedAt: Long = System.currentTimeMillis()) = RequestSnapshot(
    requests = CachedRequestList(listOf(fixtureTicket("cached-request")), updatedAt),
    mine = CachedRequestList(listOf(fixtureTicket("cached-mine", status = TicketStatus.PENDING)), updatedAt),
    implemented = CachedRequestList(listOf(fixtureTicket("cached-done", status = TicketStatus.IMPLEMENTED)), updatedAt),
)
