package io.buildthisplease.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RequestCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = RequestCacheIdentity("installation", "key")
    private val ticket = Ticket("request", "Title", "Details", TicketStatus.IN_REVIEW, createdAt = "2026-10-21", updatedAt = "2026-10-21", voteCount = 4, hasVoted = false)
    private val now = 1_800_000_000_000L

    @Test fun `cache scope changes preserve the existing installation storage alias format`() {
        val config = BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_project", BuildThisPleaseConfiguration.Environment.PRODUCTION, osVersion = "Android", identityNamespace = "account")
        assertEquals(("https://example.invalid\nbtp_pk_project\naccount").sha256Base64Url().take(18), installationStoragePrefix(config))
        assertEquals(installationStoragePrefix(config), installationStoragePrefix(config.copy(environment = BuildThisPleaseConfiguration.Environment.DEVELOPMENT, packageName = "another.package")))
        assertEquals(("https://example.invalid\nbtp_pk_project").sha256Base64Url().take(18), installationStoragePrefix(config.copy(identityNamespace = null)))
    }

    @Test fun `request disk scope includes deployment account and package but excludes private metadata`() {
        val config = BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_project", BuildThisPleaseConfiguration.Environment.PRODUCTION, osVersion = "Android", identityNamespace = "account", packageName = "host.app")
        val scope = requestCacheStorageScope(config)
        listOf(
            config.copy(baseUrl = "https://other.invalid"),
            config.copy(projectKey = "btp_pk_other"),
            config.copy(environment = BuildThisPleaseConfiguration.Environment.DEVELOPMENT),
            config.copy(identityNamespace = "other-account"),
            config.copy(packageName = "other.app"),
        ).forEach { assertFalse(scope == requestCacheStorageScope(it)) }
        assertEquals(scope, requestCacheStorageScope(config.copy(userEmail = "private@example.invalid", revenueCatAppUserId = "private-customer")))
    }

    @Test fun `disk restores optional lists and empty successful refresh removes stale items`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        cache.fetch(RequestList.MINE, identity, { identity }) { emptyList() }
        val restored = cache().snapshot(identity)!!
        assertEquals(listOf(ticket), restored.requests!!.items)
        assertEquals(emptyList<Ticket>(), restored.mine!!.items)
        assertNull(restored.implemented)
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { emptyList() }
        assertEquals(emptyList<Ticket>(), cache().snapshot(identity)!!.requests!!.items)
    }

    @Test @Config(sdk = [24, 28])
    fun `cancelled first disk restore retries the valid snapshot on the same retained cache`() = runTest {
        val original = cache()
        original.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        original.fetch(RequestList.MINE, identity, { identity }) { emptyList() }
        original.fetch(RequestList.IMPLEMENTED, identity, { identity }) { listOf(ticket.copy(status = TicketStatus.IMPLEMENTED)) }
        val expected = original.snapshot(identity)
        val retained = RequestCache(temporary.root, "scope", diskDispatcher = StandardTestDispatcher(testScheduler)) { now }

        // Enter restore, then cancel before its dispatched disk read can publish a value.
        val firstRestore = async(start = CoroutineStart.UNDISPATCHED) { retained.snapshot(identity) }
        assertFalse(firstRestore.isCompleted)
        firstRestore.cancelAndJoin()
        assertTrue(firstRestore.isCancelled)

        assertEquals(expected, retained.snapshot(identity))
        assertEquals(expected, retained.snapshot(identity))
        assertEquals(expected, cache().snapshot(identity))
    }

    @Test @Config(sdk = [24, 28])
    fun `backup only atomic snapshot restores all sections before checking the base file`() = runTest {
        val original = cache()
        original.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        original.fetch(RequestList.MINE, identity, { identity }) { listOf(ticket.copy(id = "owned", status = TicketStatus.PENDING, hasVoted = true)) }
        original.fetch(RequestList.IMPLEMENTED, identity, { identity }) { listOf(ticket.copy(id = "shipped", status = TicketStatus.IMPLEMENTED)) }
        val expected = original.snapshot(identity)
        val base = File(temporary.root, "scope.json")
        val backup = File(temporary.root, "scope.json.bak")
        assertTrue(base.renameTo(backup))
        assertFalse(base.exists())

        val restored = cache()
        assertEquals(expected, restored.snapshot(identity))
        assertEquals(expected, restored.snapshot(identity))
        assertTrue(base.exists())
        assertFalse(backup.exists())
    }

    @Test @Config(sdk = [24, 28])
    fun `backup restoration still rejects changed installation or key identity`() = runTest {
        val base = File(temporary.root, "scope.json")
        val backup = File(temporary.root, "scope.json.bak")
        for (current in listOf(identity.copy(installationId = "replacement"), identity.copy(keyId = "replacement"))) {
            cache().fetch(RequestList.MINE, identity, { identity }) { listOf(ticket.copy(hasVoted = true)) }
            assertTrue(base.renameTo(backup))
            assertNull(cache().snapshot(current))
            assertFalse(base.exists())
            assertFalse(backup.exists())
        }
    }

    @Test @Config(sdk = [24, 28])
    fun `clearing an unread backup prevents later offline restoration`() = runTest {
        cache().fetch(RequestList.MINE, identity, { identity }) { listOf(ticket) }
        val base = File(temporary.root, "scope.json")
        val backup = File(temporary.root, "scope.json.bak")
        assertTrue(base.renameTo(backup))
        cache().clear()
        assertFalse(base.exists())
        assertFalse(backup.exists())
        assertNull(cache().snapshot(identity))
    }

    @Test @Config(sdk = [24, 28])
    fun `oversized atomic backup is rejected and removed after recovery`() = runTest {
        val base = File(temporary.root, "scope.json")
        val backup = File(temporary.root, "scope.json.bak")
        RandomAccessFile(backup, "rw").use { it.setLength(RequestCache.MAX_BYTES + 1) }
        assertFalse(base.exists())
        assertNull(cache().snapshot(identity))
        assertFalse(base.exists())
        assertFalse(backup.exists())
    }

    @Test fun `offline failure leaves disk snapshot available and seven day expiry is per list`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        try { cache.fetch(RequestList.REQUESTS, identity, { identity }) { throw BuildThisPleaseException.Offline() }; fail() }
        catch (_: BuildThisPleaseException.Offline) { }
        assertEquals(ticket, cache().snapshot(identity)!!.requests!!.items.single())
        assertNotNull(cache(now + RequestCache.RETENTION_MILLIS).snapshot(identity))
        assertNull(cache(now + RequestCache.RETENTION_MILLIS + 1).snapshot(identity))
    }

    @Test fun `expiry independently removes old requests while retaining newer mine`() = runTest {
        var clock = now
        val cache = RequestCache(temporary.root, "scope") { clock }
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        clock += 2 * 24 * 60 * 60 * 1000L
        cache.fetch(RequestList.MINE, identity, { identity }) { emptyList() }
        clock = now + RequestCache.RETENTION_MILLIS + 1
        val snapshot = RequestCache(temporary.root, "scope") { clock }.snapshot(identity)!!
        assertNull(snapshot.requests)
        assertTrue(snapshot.mine!!.items.isEmpty())
    }

    @Test fun `disk budget evicts older account files across scopes`() = runTest {
        val big = ticket.copy(description = "x".repeat(3 * 1024 * 1024))
        for (scope in listOf("first", "second", "third")) {
            RequestCache(temporary.root, scope) { now }.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(big) }
        }
        assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= RequestCache.MAX_BYTES)
        assertNotNull(RequestCache(temporary.root, "third") { now }.snapshot(identity))
    }

    @Test fun `scope installation and key mismatches never expose mine or vote state`() = runTest {
        cache().fetch(RequestList.MINE, identity, { identity }) { listOf(ticket.copy(hasVoted = true)) }
        assertNull(RequestCache(temporary.root, "other-scope") { now }.snapshot(identity))
        assertNull(cache().snapshot(identity.copy(installationId = "replacement")))
        cache().fetch(RequestList.MINE, identity, { identity }) { listOf(ticket) }
        assertNull(cache().snapshot(identity.copy(keyId = "new-key")))
        assertNull(cache().snapshot(null))
    }

    @Test fun `concurrent endpoint reads share the caller owned request`() = runTest {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun fetch() = cache.fetch(RequestList.REQUESTS, identity, { identity }) {
            calls++; entered.complete(Unit); release.await(); listOf(ticket)
        }
        val first = async { fetch() }
        entered.await()
        val secondEntered = CompletableDeferred<Unit>()
        val second = async { secondEntered.complete(Unit); fetch() }
        secondEntered.await()
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test fun `new vote create and detail changes survive a delayed list response`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        cache.fetch(RequestList.MINE, identity, { identity }) { emptyList() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = async { cache.fetch(RequestList.REQUESTS, identity, { identity }) { entered.complete(Unit); release.await(); listOf(ticket) } }
        entered.await()
        val voted = ticket.copy(voteCount = 5, hasVoted = true)
        cache.update(voted, false, cache.operation(), identity)
        val created = ticket.copy(id = "created", status = TicketStatus.PENDING)
        cache.update(created, true, cache.operation(), identity)
        release.complete(Unit)
        assertEquals(voted, delayed.await().single())
        assertEquals(created, cache().snapshot(identity)!!.mine!!.items.single())
        val implemented = voted.copy(status = TicketStatus.IMPLEMENTED)
        cache.update(implemented, false, cache.operation(), identity)
        assertTrue(cache().snapshot(identity)!!.requests!!.items.isEmpty())
    }

    @Test fun `detail after create retains mine membership across a delayed empty mine response`() = runTest {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mine = async { cache.fetch(RequestList.MINE, identity, { identity }) { entered.complete(Unit); release.await(); emptyList() } }
        entered.await()
        val created = ticket.copy(status = TicketStatus.PENDING)
        cache.update(created, true, cache.operation(), identity)
        val refreshed = created.copy(description = "Refreshed detail")
        cache.update(refreshed, false, cache.operation(), identity)
        release.complete(Unit)
        assertEquals(listOf(refreshed), mine.await())
        assertEquals(listOf(refreshed), cache().snapshot(identity)!!.mine!!.items)
    }

    @Test fun `clear is a barrier for delayed list and mutation writes and detaches coalescing`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        val beforeClear = cache.operation()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = async { cache.fetch(RequestList.REQUESTS, identity, { identity }) { entered.complete(Unit); release.await(); listOf(ticket) } }
        entered.await()
        cache.clear()
        assertNull(cache().snapshot(identity))
        cache.update(ticket, true, beforeClear, identity)
        assertNull(cache.snapshot(identity))
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { emptyList() }
        release.complete(Unit)
        try { delayed.await(); fail() } catch (_: CancellationException) { }
        assertTrue(cache().snapshot(identity)!!.requests!!.items.isEmpty())
    }

    @Test fun `an active waiter takes over when the original fetch owner is cancelled`() = runTest {
        val cache = cache()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun fetch() = cache.fetch(RequestList.REQUESTS, identity, { identity }) {
            calls++
            if (calls == 1) { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            listOf(ticket)
        }
        val owner = async { fetch() }
        entered.await()
        val waiterStarted = CompletableDeferred<Unit>()
        val waiter = async { waiterStarted.complete(Unit); fetch() }
        waiterStarted.await()
        owner.cancel()
        assertEquals(listOf(ticket), waiter.await())
        assertEquals(2, calls)
    }

    @Test fun `create before a full load persists a partial mine list that still needs refresh`() = runTest {
        val cache = cache()
        cache.update(ticket, true, cache.operation(), identity)
        val mine = cache().snapshot(identity)!!.mine!!
        assertEquals(listOf(ticket), mine.items)
        assertFalse(mine.isComplete)
        assertFalse(mine.isFresh(nowMillis = now))
    }

    @Test fun `installation replacement discards old cache and transparently retries an active fetch`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.MINE, identity, { identity }) { listOf(ticket.copy(hasVoted = true)) }
        var current = identity
        var calls = 0
        val fresh = ticket.copy(id = "new-installation", hasVoted = false)
        val result = cache.fetch(RequestList.REQUESTS, identity, { current }) {
            calls++
            if (calls == 1) {
                current = identity.copy(installationId = "replacement")
                cache.clear(installationReplacement = true)
            }
            listOf(fresh)
        }
        assertEquals(listOf(fresh), result)
        assertEquals(2, calls)
        assertNull(cache.snapshot(current)!!.mine)
        assertEquals(listOf(fresh), cache().snapshot(current)!!.requests!!.items)
    }

    @Test fun `detail started before a vote preserves the newer ticket in memory and disk`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        val detail = cache.operation(retainTicketChanges = true)
        val voted = ticket.copy(hasVoted = true, voteCount = 5)
        cache.update(voted, false, cache.operation(), identity)
        assertEquals(voted, cache.update(ticket, false, detail, identity, authoritative = false))
        cache.finishTicketOperation()
        assertEquals(voted, cache().snapshot(identity)!!.requests!!.items.single())
    }

    @Test fun `later detail requests can publish newer server data than an earlier completed detail`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        val first = cache.operation(retainTicketChanges = true)
        val second = cache.operation(retainTicketChanges = true)
        cache.update(ticket, false, first, identity, authoritative = false)
        val newer = ticket.copy(title = "Newer server title")
        assertEquals(newer, cache.update(newer, false, second, identity, authoritative = false))
        assertEquals(newer, cache.update(ticket, false, first, identity, authoritative = false))
        cache.finishTicketOperation()
        cache.finishTicketOperation()
        assertEquals(newer, cache().snapshot(identity)!!.requests!!.items.single())
    }

    @Test fun `stale detail completions retain winning vote provenance for other outstanding details`() = runTest {
        val cache = cache()
        cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) }
        val first = cache.operation(retainTicketChanges = true)
        val second = cache.operation(retainTicketChanges = true)
        val voted = ticket.copy(hasVoted = true, voteCount = 5)
        cache.update(voted, false, cache.operation(), identity)
        assertEquals(voted, cache.update(ticket, false, first, identity, authoritative = false))
        assertEquals(voted, cache.update(ticket, false, second, identity, authoritative = false))
        cache.finishTicketOperation()
        cache.finishTicketOperation()
        assertEquals(voted, cache().snapshot(identity)!!.requests!!.items.single())
    }

    @Test fun `held old installation detail retries for new identity but manual clear cancels it`() = runTest {
        for (replacement in listOf(true, false)) {
            val cache = cache()
            var current = identity
            var calls = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fresh = ticket.copy(title = "New identity", hasVoted = true)
            val detail = async {
                cache.ticket({ current }) {
                    calls++
                    if (calls == 1) { entered.complete(Unit); release.await(); ticket } else fresh
                }
            }
            entered.await()
            cache.clear(installationReplacement = replacement)
            current = identity.copy(installationId = "replacement")
            release.complete(Unit)
            if (replacement) { assertEquals(fresh, detail.await()); assertEquals(2, calls) }
            else try { detail.await(); fail() } catch (_: CancellationException) { }
        }
    }

    @Test fun `public list and preload calls suspended in credential lookup cannot outlive clear`() = runTest {
        for (preload in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var firstRead = true
            val credentials = object : CredentialStore {
                override suspend fun readSession(): StoredSession {
                    if (firstRead) { firstRead = false; entered.complete(Unit); release.await() }
                    return StoredSession("token", "installation", "2099-01-01T00:00:00Z")
                }
                override suspend fun writeSession(session: StoredSession) {}
                override suspend fun clearSession() {}
            }
            var calls = 0
            val transport = object : BuildThisPleaseTransport {
                override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                    calls++; return TransportResponse(200, "{\"items\":[]}".toByteArray())
                }
            }
            val client = BuildThisPleaseClient(
                ApplicationProvider.getApplicationContext<Context>(),
                BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_test", BuildThisPleaseConfiguration.Environment.PRODUCTION, identityNamespace = UUID.randomUUID().toString()),
                transport, object : IntegrityTokenProvider { override suspend fun token(cloudProjectNumber: Long, requestHash: String) = "integrity" },
                object : InstallationSigner {
                    override val keyId = "key"
                    override fun hasKey() = true
                    override fun publicKeySpki() = byteArrayOf(1)
                    override fun sign(requestHash: String) = byteArrayOf(1)
                    override fun reset() {}
                }, credentials,
            )
            val request = async { if (preload) client.preloadRequests() else client.requests() }
            entered.await()
            client.clearRequestCache()
            release.complete(Unit)
            try { request.await(); fail() } catch (_: CancellationException) { }
            assertEquals(0, calls)
            assertNull(client.cachedRequests())
        }
    }

    @Test fun `malformed oversized and unavailable disk storage are best effort`() = runTest {
        File(temporary.root, "scope.json").writeText("broken json")
        assertNull(cache().snapshot(identity))
        val unavailable = File(temporary.root, "file").apply { writeText("not a directory") }
        val cache = RequestCache(unavailable, "scope") { now }
        assertEquals(listOf(ticket), cache.fetch(RequestList.REQUESTS, identity, { identity }) { listOf(ticket) })
        assertEquals(ticket, cache.snapshot(identity)!!.requests!!.items.single())
        val huge = ticket.copy(description = "x".repeat(RequestCache.MAX_BYTES.toInt()))
        cache().fetch(RequestList.REQUESTS, identity, { identity }) { listOf(huge) }
        assertNull(cache().snapshot(identity))
    }

    @Test fun `concrete client preloads once restores offline and refresh getters remain fresh`() = runTest {
        val namespace = UUID.randomUUID().toString()
        val credentials = object : CredentialStore {
            override suspend fun readSession() = StoredSession("secret-token", "installation", "2099-01-01T00:00:00Z")
            override suspend fun writeSession(session: StoredSession) {}
            override suspend fun clearSession() {}
        }
        val signer = object : InstallationSigner {
            override val keyId = "key"
            override fun hasKey() = true
            override fun publicKeySpki() = byteArrayOf(1)
            override fun sign(requestHash: String) = byteArrayOf(1)
            override fun reset() {}
        }
        var calls = 0
        var offline = false
        val transport = object : BuildThisPleaseTransport {
            override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                assertEquals("GET", method)
                assertEquals("Bearer secret-token", headers["Authorization"])
                calls++
                if (offline) throw BuildThisPleaseException.Offline()
                return TransportResponse(200, "{\"items\":[]}".toByteArray())
            }
        }
        fun client() = BuildThisPleaseClient(
            ApplicationProvider.getApplicationContext<Context>(),
            BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_test", BuildThisPleaseConfiguration.Environment.PRODUCTION, identityNamespace = namespace),
            transport, object : IntegrityTokenProvider { override suspend fun token(cloudProjectNumber: Long, requestHash: String) = "integrity" }, signer, credentials,
        )
        val client = client()
        client.preloadRequests()
        assertEquals(3, calls)
        client.preloadRequests()
        assertEquals(3, calls)
        client.requests()
        assertEquals(4, calls)
        offline = true
        val restored = client()
        assertNotNull(restored.cachedRequests()!!.mine)
        restored.preloadRequests()
        assertEquals(4, calls)
        restored.clearRequestCache()
        assertNull(client().cachedRequests())
    }

    @Test fun `concrete client serializes vote commits through publication and overlays delayed lists`() = runTest {
        val namespace = UUID.randomUUID().toString()
        val firstCommitted = CompletableDeferred<Unit>()
        val releaseFirstResponse = CompletableDeferred<Unit>()
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val secondCommitted = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        var serverTicket = ticket
        var listCalls = 0
        val transport = object : BuildThisPleaseTransport {
            override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                val response = when {
                    url.endsWith("/assertions/challenges") -> challengeResponse
                    url.endsWith("/vote") -> {
                        commits += method
                        serverTicket = ticket.copy(hasVoted = method == "PUT", voteCount = if (method == "PUT") 5 else 4)
                        val committed = serverTicket
                        if (method == "PUT") { firstCommitted.complete(Unit); releaseFirstResponse.await() }
                        else secondCommitted.complete(Unit)
                        buildThisPleaseJson.encodeToString(TicketEnvelope(committed))
                    }
                    else -> {
                        val captured = serverTicket
                        if (++listCalls == 2) { listStarted.complete(Unit); releaseList.await() }
                        "{\"items\":" + buildThisPleaseJson.encodeToString(listOf(captured)) + "}"
                    }
                }
                return TransportResponse(200, response.toByteArray())
            }
        }
        val client = client(transport, namespace)
        client.requests()
        val vote = async(start = CoroutineStart.UNDISPATCHED) { client.setVote(ticket.id, true) }
        firstCommitted.await()
        // The list can reach the server while the mutation's response is held.
        val staleList = async(start = CoroutineStart.UNDISPATCHED) { client.requests() }
        listStarted.await()
        val unvote = async(start = CoroutineStart.UNDISPATCHED) { client.setVote(ticket.id, false) }
        assertFalse(secondCommitted.isCompleted)

        releaseFirstResponse.complete(Unit)
        assertEquals(ticket.copy(hasVoted = true, voteCount = 5), vote.await())
        assertEquals(ticket, unvote.await())
        assertEquals(listOf("PUT", "DELETE"), commits)
        assertEquals(ticket, serverTicket)
        assertEquals(listOf(ticket), client.cachedRequests()!!.requests!!.items)
        assertEquals(listOf(ticket), client(transport, namespace).cachedRequests()!!.requests!!.items)

        releaseList.complete(Unit)
        assertEquals(listOf(ticket), staleList.await())
        assertEquals(listOf(ticket), client.cachedRequests()!!.requests!!.items)
        assertEquals(listOf(ticket), client(transport, namespace).cachedRequests()!!.requests!!.items)
    }

    @Test fun `concrete merged detail replaces cached source preserves mine and survives old lists and reopen`() = runTest {
        for (status in listOf(TicketStatus.PLANNED, TicketStatus.IMPLEMENTED, TicketStatus.REJECTED)) {
            for (canonicalPresent in listOf(false, true)) {
                val source = ticket.copy(id = "source")
                val canonical = ticket.copy(id = "canonical", status = status, description = "Canonical detail")
                val initial = listOf(source) + if (canonicalPresent) listOf(canonical.copy(status = TicketStatus.IN_REVIEW)) else emptyList()
                val requestsStarted = CompletableDeferred<Unit>()
                val releaseRequests = CompletableDeferred<Unit>()
                val mineStarted = CompletableDeferred<Unit>()
                val releaseMine = CompletableDeferred<Unit>()
                var requestsCalls = 0
                var mineCalls = 0
                var detailCalls = 0
                val transport = object : BuildThisPleaseTransport {
                    override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                        assertEquals("GET", method)
                        val response = when {
                            url.endsWith("/v1/tickets/source") -> {
                                detailCalls++
                                buildThisPleaseJson.encodeToString(TicketEnvelope(canonical))
                            }
                            url.contains("/v1/me/tickets?") -> {
                                when (++mineCalls) {
                                    1 -> page(initial)
                                    2 -> { mineStarted.complete(Unit); releaseMine.await(); page(emptyList()) }
                                    else -> page(emptyList())
                                }
                            }
                            url.contains("/v1/tickets?") -> {
                                if (++requestsCalls == 2) { requestsStarted.complete(Unit); releaseRequests.await() }
                                page(initial)
                            }
                            else -> page(emptyList())
                        }
                        return TransportResponse(200, response.toByteArray())
                    }
                }
                val namespace = UUID.randomUUID().toString()
                val client = client(transport, namespace)
                client.requests(); client.myRequests(); client.implementedRequests()
                val oldRequests = async(start = CoroutineStart.UNDISPATCHED) { client.requests() }
                requestsStarted.await()
                val oldMine = async(start = CoroutineStart.UNDISPATCHED) { client.myRequests() }
                mineStarted.await()
                assertEquals(canonical, client.ticket(source.id))
                fun assertSnapshot(snapshot: RequestSnapshot) {
                    assertEquals(if (status == TicketStatus.PLANNED) listOf(canonical) else emptyList<Ticket>(), snapshot.requests!!.items)
                    assertEquals(listOf(canonical), snapshot.mine!!.items)
                    assertEquals(if (status == TicketStatus.IMPLEMENTED) listOf(canonical) else emptyList<Ticket>(), snapshot.implemented!!.items)
                }
                assertSnapshot(client.cachedRequests()!!)
                assertSnapshot(client(transport, namespace).cachedRequests()!!)
                releaseRequests.complete(Unit); releaseMine.complete(Unit)
                assertEquals(if (status == TicketStatus.PLANNED) listOf(canonical) else emptyList<Ticket>(), oldRequests.await())
                assertEquals(listOf(canonical), oldMine.await())
                assertSnapshot(client.cachedRequests()!!)
                assertSnapshot(client(transport, namespace).cachedRequests()!!)
                assertEquals(1, detailCalls)
                // A fetch begun after the detail remains authoritative about ownership.
                assertTrue(client.myRequests().isEmpty())
                assertEquals(canonical, client.ticket(source.id))
                assertTrue(client.cachedRequests()!!.mine!!.items.isEmpty())
                assertTrue(client(transport, namespace).cachedRequests()!!.mine!!.items.isEmpty())
            }
        }
    }

    @Test fun `concrete merge chain coalesces aliases and older detail without reviving removed mine membership`() = runTest {
        for (removeMine in listOf(false, true)) {
            val source = ticket.copy(id = "source")
            val canonical = ticket.copy(id = "canonical", status = TicketStatus.PLANNED)
            val destination = ticket.copy(id = "destination", status = TicketStatus.IMPLEMENTED, voteCount = null, hasVoted = null)
            val requestsStarted = CompletableDeferred<Unit>()
            val releaseRequests = CompletableDeferred<Unit>()
            val detailStarted = CompletableDeferred<Unit>()
            val releaseDetail = CompletableDeferred<Unit>()
            var requestsCalls = 0
            var sourceDetailCalls = 0
            var mineCalls = 0
            val transport = object : BuildThisPleaseTransport {
                override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                    val response = when {
                        url.endsWith("/v1/tickets/source") -> {
                            if (++sourceDetailCalls == 2) { detailStarted.complete(Unit); releaseDetail.await() }
                            buildThisPleaseJson.encodeToString(TicketEnvelope(canonical))
                        }
                        url.endsWith("/v1/tickets/canonical") -> buildThisPleaseJson.encodeToString(TicketEnvelope(destination))
                        url.contains("/v1/me/tickets?") -> page(if (++mineCalls == 1) listOf(source, canonical) else emptyList())
                        url.contains("/v1/tickets?") -> {
                            if (++requestsCalls == 2) { requestsStarted.complete(Unit); releaseRequests.await() }
                            page(listOf(source, canonical))
                        }
                        else -> page(emptyList())
                    }
                    return TransportResponse(200, response.toByteArray())
                }
            }
            val namespace = UUID.randomUUID().toString()
            val client = client(transport, namespace)
            client.requests(); client.myRequests(); client.implementedRequests()
            val oldRequests = async(start = CoroutineStart.UNDISPATCHED) { client.requests() }
            requestsStarted.await()
            assertEquals(canonical, client.ticket(source.id))
            val oldDetail = async(start = CoroutineStart.UNDISPATCHED) { client.ticket(source.id) }
            detailStarted.await()
            if (removeMine) assertTrue(client.myRequests().isEmpty())
            assertEquals(destination, client.ticket(canonical.id))
            releaseDetail.complete(Unit)
            assertEquals(destination, oldDetail.await())
            releaseRequests.complete(Unit)
            assertTrue(oldRequests.await().isEmpty())
            for (snapshot in listOf(client.cachedRequests()!!, client(transport, namespace).cachedRequests()!!)) {
                assertTrue(snapshot.requests!!.items.isEmpty())
                assertEquals(if (removeMine) emptyList<Ticket>() else listOf(destination), snapshot.mine!!.items)
                assertEquals(listOf(destination), snapshot.implemented!!.items)
            }
        }
    }

    @Test fun `failed and cancelled votes release the queued same ticket mutation`() = runTest {
        for (cancelFirst in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondCommitted = CompletableDeferred<Unit>()
            val transport = object : BuildThisPleaseTransport {
                override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                    val response = when {
                        url.endsWith("/assertions/challenges") -> challengeResponse
                        url.endsWith("/vote") && method == "PUT" -> {
                            entered.complete(Unit)
                            release.await()
                            throw BuildThisPleaseException.Offline()
                        }
                        url.endsWith("/vote") -> { secondCommitted.complete(Unit); buildThisPleaseJson.encodeToString(TicketEnvelope(ticket)) }
                        else -> "{\"items\":" + buildThisPleaseJson.encodeToString(listOf(ticket)) + "}"
                    }
                    return TransportResponse(200, response.toByteArray())
                }
            }
            val client = client(transport)
            client.requests()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try { client.setVote(ticket.id, true); fail("The first vote must fail") }
                catch (_: BuildThisPleaseException.Offline) { }
            }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { client.setVote(ticket.id, false) }
            assertFalse(secondCommitted.isCompleted)
            if (cancelFirst) { first.cancelAndJoin(); assertTrue(first.isCancelled) }
            else { release.complete(Unit); first.await() }
            assertEquals(ticket, second.await())
            assertEquals(listOf(ticket), client.cachedRequests()!!.requests!!.items)
        }
    }

    @Test fun `votes queued before clear cannot restore memory or disk after the barrier`() = runTest {
        val namespace = UUID.randomUUID().toString()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : BuildThisPleaseTransport {
            override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                val response = when {
                    url.endsWith("/assertions/challenges") -> challengeResponse
                    url.endsWith("/vote") -> {
                        if (method == "PUT") { entered.complete(Unit); release.await() }
                        buildThisPleaseJson.encodeToString(TicketEnvelope(ticket.copy(hasVoted = method == "PUT")))
                    }
                    else -> "{\"items\":" + buildThisPleaseJson.encodeToString(listOf(ticket)) + "}"
                }
                return TransportResponse(200, response.toByteArray())
            }
        }
        val client = client(transport, namespace)
        client.requests()
        val vote = async(start = CoroutineStart.UNDISPATCHED) { client.setVote(ticket.id, true) }
        entered.await()
        val unvote = async(start = CoroutineStart.UNDISPATCHED) { client.setVote(ticket.id, false) }
        client.clearRequestCache()
        release.complete(Unit)
        vote.await()
        unvote.await()
        assertNull(client.cachedRequests())
        assertNull(client(transport, namespace).cachedRequests())
    }

    @Test fun `held create preserves a newer canonical detail and adds mine across a delayed list`() = runTest {
        val namespace = UUID.randomUUID().toString()
        val created = ticket.copy(status = TicketStatus.PENDING, hasVoted = true, voteCount = 1)
        val canonical = created.copy(status = TicketStatus.PLANNED, description = "Canonical detail after moderation")
        val createCommitted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val mineStarted = CompletableDeferred<Unit>()
        val releaseMine = CompletableDeferred<Unit>()
        var mineCalls = 0
        val transport = object : BuildThisPleaseTransport {
            override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                val response = when {
                    url.endsWith("/assertions/challenges") -> challengeResponse
                    method == "POST" && url.endsWith("/v1/tickets") -> {
                        createCommitted.complete(Unit); releaseCreate.await()
                        buildThisPleaseJson.encodeToString(TicketEnvelope(created))
                    }
                    url.endsWith("/v1/tickets/${ticket.id}") -> buildThisPleaseJson.encodeToString(TicketEnvelope(canonical))
                    else -> {
                        if (url.contains("/v1/me/tickets") && ++mineCalls == 2) { mineStarted.complete(Unit); releaseMine.await() }
                        "{\"items\":[]}"
                    }
                }
                return TransportResponse(200, response.toByteArray())
            }
        }
        val client = client(transport, namespace)
        client.requests()
        client.myRequests()
        val create = async(start = CoroutineStart.UNDISPATCHED) { client.createTicket("Title", "Details", null, "creation") }
        createCommitted.await()
        assertEquals(canonical, client.ticket(ticket.id))
        val mine = async(start = CoroutineStart.UNDISPATCHED) { client.myRequests() }
        mineStarted.await()
        releaseCreate.complete(Unit)
        assertEquals(canonical, create.await())
        assertEquals(listOf(canonical), client.cachedRequests()!!.mine!!.items)
        releaseMine.complete(Unit)
        assertEquals(listOf(canonical), mine.await())
        assertEquals(listOf(canonical), client.cachedRequests()!!.requests!!.items)
        assertEquals(listOf(canonical), client(transport, namespace).cachedRequests()!!.mine!!.items)
    }

    @Test @Config(sdk = [24])
    fun `concrete client recovers a replaced installation and still returns fresh requests`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).apply {
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
        }
        shadowOf(context.packageManager).installPackage(info)
        var saved: StoredSession? = StoredSession("old-token", "old-installation", "2099-01-01T00:00:00Z")
        val credentials = object : CredentialStore {
            override suspend fun readSession() = saved
            override suspend fun writeSession(session: StoredSession) { saved = session }
            override suspend fun clearSession() { saved = null }
        }
        val signer = object : InstallationSigner {
            override val keyId = "key"
            override fun hasKey() = true
            override fun publicKeySpki() = byteArrayOf(1)
            override fun sign(requestHash: String) = byteArrayOf(1)
            override fun reset() {}
        }
        var invalid = false
        var recoveryCount = 0
        val fresh = ticket.copy(id = "fresh", hasVoted = false)
        val transport = object : BuildThisPleaseTransport {
            override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
                val response = when {
                    url.endsWith("/recovery/challenges") -> """{"challengeId":"challenge","requestHash":"hash","cloudProjectNumber":"1","expiresAt":"2099-01-01T00:00:00Z"}"""
                    url.endsWith("/recoveries") -> {
                        recoveryCount++
                        """{"sessionToken":"new-token","installationId":"new-installation","expiresAt":"2099-01-01T00:00:00Z"}"""
                    }
                    invalid && headers["Authorization"] == "Bearer old-token" -> return TransportResponse(401, """{"error":{"code":"installation_session_invalid","message":"Expired"}}""".toByteArray())
                    else -> "{\"items\":" + buildThisPleaseJson.encodeToString(listOf(if (invalid) fresh else ticket.copy(hasVoted = true))) + "}"
                }
                return TransportResponse(200, response.toByteArray())
            }
        }
        val client = BuildThisPleaseClient(
            context, BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_test", BuildThisPleaseConfiguration.Environment.PRODUCTION, identityNamespace = UUID.randomUUID().toString()),
            transport, object : IntegrityTokenProvider { override suspend fun token(cloudProjectNumber: Long, requestHash: String) = "integrity" }, signer, credentials,
        )
        client.myRequests()
        assertTrue(client.cachedRequests()!!.mine!!.items.single().hasVoted == true)
        invalid = true
        assertEquals(listOf(fresh), client.requests())
        assertEquals(1, recoveryCount)
        assertNull(client.cachedRequests()!!.mine)
        assertEquals(listOf(fresh), client.cachedRequests()!!.requests!!.items)
    }

    private fun client(transport: BuildThisPleaseTransport, namespace: String = UUID.randomUUID().toString()) = BuildThisPleaseClient(
        ApplicationProvider.getApplicationContext<Context>(),
        BuildThisPleaseConfiguration("https://example.invalid", "btp_pk_test", BuildThisPleaseConfiguration.Environment.PRODUCTION, identityNamespace = namespace),
        transport,
        object : IntegrityTokenProvider { override suspend fun token(cloudProjectNumber: Long, requestHash: String) = "integrity" },
        object : InstallationSigner {
            override val keyId = "key"
            override fun hasKey() = true
            override fun publicKeySpki() = byteArrayOf(1)
            override fun sign(requestHash: String) = byteArrayOf(1)
            override fun reset() {}
        },
        object : CredentialStore {
            override suspend fun readSession() = StoredSession("token", "installation", "2099-01-01T00:00:00Z")
            override suspend fun writeSession(session: StoredSession) {}
            override suspend fun clearSession() {}
        },
    )

    private fun page(tickets: List<Ticket>) = "{\"items\":" + buildThisPleaseJson.encodeToString(tickets) + "}"

    private val challengeResponse = """{"challengeId":"challenge","requestHash":"hash","cloudProjectNumber":"1","expiresAt":"2099-01-01T00:00:00Z"}"""
    private fun cache(at: Long = now) = RequestCache(temporary.root, "scope") { at }
}
