package io.buildthisplease.core

import android.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** One successfully fetched list. An empty [items] list is a cached empty result. */
@Serializable
data class CachedRequestList(val items: List<Ticket>, val updatedAt: Long, val isComplete: Boolean = true) {
    /** Freshness is deliberately short; retained offline data can be older. Times are epoch milliseconds. */
    fun isFresh(maxAgeMillis: Long = 60_000, nowMillis: Long = System.currentTimeMillis()): Boolean =
        isComplete && nowMillis - updatedAt in 0..maxAgeMillis
}

/** Null sections have never loaded (or expired); present sections may be empty. */
@Serializable
data class RequestSnapshot(
    val requests: CachedRequestList? = null,
    val mine: CachedRequestList? = null,
    val implemented: CachedRequestList? = null,
)

internal enum class RequestList { REQUESTS, MINE, IMPLEMENTED }
internal data class RequestCacheIdentity(val installationId: String, val keyId: String)
internal data class RequestCacheOperation(val generation: Long, val revision: Long, val clearGeneration: Long, val sequence: Long)
private data class TicketChange(
    val ticket: Ticket,
    val revision: Long,
    val created: Boolean,
    val authoritative: Boolean,
    val operationSequence: Long,
    val replacedIds: Set<String>,
    val mine: Boolean,
) {
    fun matches(id: String) = ticket.id == id || id in replacedIds
}
private class RequestCacheClearedException : CancellationException("Request cache was cleared.")
private class RequestCacheIdentityChangedException : CancellationException("Request installation changed.")

/** No background scope: the caller that starts a fetch owns it; other callers await its result. */
internal class RequestCache(
    directory: File,
    scope: String,
    private val diskDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val directory = directory
    private val file = AtomicFile(File(directory, "$scope.json"))
    private val mutex = Mutex()
    private var loaded = false
    private var identity: RequestCacheIdentity? = null
    private var snapshot: RequestSnapshot? = null
    private var generation = 0L
    private var clearGeneration = 0L
    private var revision = 0L
    private var operationSequence = 0L
    private var activeTicketOperations = 0
    private val changes = mutableMapOf<String, TicketChange>()
    private val inFlight = mutableMapOf<RequestList, CompletableDeferred<List<Ticket>>>()

    suspend fun snapshot(currentIdentity: RequestCacheIdentity?): RequestSnapshot? = mutex.withLock {
        restore(currentIdentity)
        snapshot = snapshot?.unexpired(now())
        snapshot
    }

    suspend fun clearBarrier(): Long = mutex.withLock { clearGeneration }

    suspend fun operation(retainTicketChanges: Boolean = false, clearBarrier: Long? = null): RequestCacheOperation = mutex.withLock {
        if (clearBarrier != null && clearBarrier != clearGeneration) throw RequestCacheClearedException()
        if (retainTicketChanges) activeTicketOperations++
        RequestCacheOperation(generation, revision, clearGeneration, ++operationSequence)
    }

    suspend fun ticket(identityAfterFetch: suspend () -> RequestCacheIdentity?, requestedId: String? = null, network: suspend () -> Ticket): Ticket {
        val barrier = clearBarrier()
        return ticket(barrier, identityAfterFetch, requestedId, network)
    }

    private suspend fun ticket(barrier: Long, identityAfterFetch: suspend () -> RequestCacheIdentity?, requestedId: String?, network: suspend () -> Ticket): Ticket {
        val operation = operation(retainTicketChanges = true, clearBarrier = barrier)
        try {
            return update(network(), false, operation, identityAfterFetch(), authoritative = false, requestedId = requestedId)
        } catch (failure: RequestCacheIdentityChangedException) {
            currentCoroutineContext().ensureActive()
            return ticket(barrier, identityAfterFetch, requestedId, network)
        } finally { finishTicketOperation() }
    }

    suspend fun finishTicketOperation() = withContext(NonCancellable) {
        mutex.withLock {
            activeTicketOperations--
            if (inFlight.isEmpty() && activeTicketOperations == 0) changes.clear()
        }
    }

    suspend fun clear(installationReplacement: Boolean = false) = mutex.withLock {
        generation++
        if (!installationReplacement) clearGeneration++
        revision++
        snapshot = null
        changes.clear()
        inFlight.values.forEach { it.completeExceptionally(if (installationReplacement) RequestCacheIdentityChangedException() else RequestCacheClearedException()) }
        inFlight.clear()
        loaded = true
        disk { file.delete() }
    }

    suspend fun fetch(
        section: RequestList,
        currentIdentity: RequestCacheIdentity?,
        identityAfterFetch: suspend () -> RequestCacheIdentity?,
        clearBarrier: Long? = null,
        network: suspend () -> List<Ticket>,
    ): List<Ticket> {
        val (pending, operation, initialClearGeneration) = mutex.withLock {
            if (clearBarrier != null && clearBarrier != clearGeneration) throw RequestCacheClearedException()
            restore(currentIdentity)
            inFlight[section]?.let { return@withLock Triple(it, null, clearGeneration) }
            val pending = CompletableDeferred<List<Ticket>>()
            inFlight[section] = pending
            Triple(pending, RequestCacheOperation(generation, revision, clearGeneration, ++operationSequence), clearGeneration)
        }
        if (operation == null) {
            try { return pending.await() }
            catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (failure is RequestCacheClearedException) throw failure
                // The owner left its lifecycle. An active waiter owns the replacement fetch.
                return fetch(section, identityAfterFetch(), identityAfterFetch, initialClearGeneration, network)
            }
        }
        try {
            val fetched = network()
            val current = identityAfterFetch()
            val result = mutex.withLock {
                if (operation.generation != generation) {
                    if (operation.clearGeneration != clearGeneration) throw RequestCacheClearedException()
                    throw RequestCacheIdentityChangedException()
                }
                else {
                    adopt(current)
                    val merged = merge(fetched, section, changes.values.filter { it.revision > operation.revision })
                    if (current != null) {
                        val list = CachedRequestList(merged, now())
                        snapshot = (snapshot ?: RequestSnapshot()).withList(section, list)
                        persist()
                    }
                    merged
                }
            }
            pending.complete(result)
            return result
        } catch (failure: Throwable) {
            // Remove first, so an active waiter recovering from owner cancellation cannot rejoin it.
            withContext(NonCancellable) {
                mutex.withLock { if (inFlight[section] === pending) inFlight.remove(section) }
                pending.completeExceptionally(failure)
            }
            if (failure is RequestCacheIdentityChangedException) {
                currentCoroutineContext().ensureActive()
                return fetch(section, identityAfterFetch(), identityAfterFetch, initialClearGeneration, network)
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight[section] === pending) inFlight.remove(section)
                    if (inFlight.isEmpty() && activeTicketOperations == 0) changes.clear()
                }
            }
        }
    }

    suspend fun update(ticket: Ticket, created: Boolean, operation: RequestCacheOperation, current: RequestCacheIdentity?, authoritative: Boolean = true, requestedId: String? = null): Ticket = mutex.withLock {
        if (operation.generation != generation) {
            if (!authoritative) {
                if (operation.clearGeneration != clearGeneration) throw RequestCacheClearedException()
                throw RequestCacheIdentityChangedException()
            }
            return@withLock ticket
        }
        restore(current)
        if (current == null) return@withLock ticket
        // Detail may resolve a merged source to a different ID without returning an alias.
        // Keep this relationship only while overlapping operations need revision overlays.
        val related = changes.values.filter { it.matches(ticket.id) || requestedId?.let(it::matches) == true }
        val previous = related.maxByOrNull(TicketChange::revision)
        val retained = previous?.takeIf {
            ticket.id in it.replacedIds || (it.ticket.id == ticket.id && (!authoritative || created) &&
                ((it.authoritative && it.revision > operation.revision) || (!it.authoritative && it.operationSequence > operation.sequence)))
        }
        val effective = retained?.ticket ?: ticket
        val ids = related.flatMapTo(mutableSetOf()) { it.replacedIds + it.ticket.id }.apply {
            add(ticket.id)
            requestedId?.let(::add)
        }
        val replacedIds = ids - effective.id
        // Transfer ownership from any replaced source, but let a later authoritative Mine
        // refresh remove it. Old change metadata must not make membership permanent.
        val mine = created || snapshot?.mine?.items?.any { it.id in ids } == true
        val wasCreated = created || related.any(TicketChange::created)
        val change = retained?.copy(
            created = wasCreated,
            replacedIds = replacedIds,
            mine = mine,
            revision = if (wasCreated != retained.created || replacedIds != retained.replacedIds || mine != retained.mine) ++revision else retained.revision,
        ) ?: TicketChange(effective, ++revision, wasCreated, authoritative, operation.sequence, replacedIds, mine)
        related.forEach { changes.remove(it.ticket.id) }
        if (inFlight.isNotEmpty() || activeTicketOperations > 0) changes[effective.id] = change
        snapshot = (snapshot ?: if (created) RequestSnapshot() else null)?.let { value ->
            value.copy(
                requests = value.requests?.let { it.copy(items = merge(it.items, RequestList.REQUESTS, listOf(change))) },
                mine = value.mine?.let { it.copy(items = merge(it.items, RequestList.MINE, listOf(change))) }
                    ?: if (created) CachedRequestList(listOf(effective), now(), isComplete = false) else null,
                implemented = value.implemented?.let { it.copy(items = merge(it.items, RequestList.IMPLEMENTED, listOf(change))) },
            )
        }
        persist()
        effective
    }

    private suspend fun restore(current: RequestCacheIdentity?) {
        if (loaded) { adopt(current); return }
        val restored = if (current == null) null else disk {
            val stored = file.openRead().use { stream ->
                // On older Android an interrupted write may leave only the backup.
                // Let AtomicFile restore it before inspecting the committed file.
                if (file.baseFile.length() > MAX_BYTES) { file.delete(); return@disk null }
                buildThisPleaseJson.decodeFromString<StoredRequestSnapshot>(stream.readBytes().decodeToString())
            }
            if (stored.version == 1 && stored.installationId == current.installationId && stored.keyId == current.keyId) {
                stored.snapshot.unexpired(now())
            } else { file.delete(); null }
        }
        // Cancellation during the first disk read must leave this retained cache retryable.
        identity = current
        snapshot = restored
        loaded = true
    }

    private suspend fun adopt(current: RequestCacheIdentity?) {
        if (identity == current) return
        identity = current
        snapshot = null
        changes.clear()
        disk { file.delete() }
    }

    private suspend fun persist() {
        val value = snapshot ?: return
        val current = identity ?: return
        disk {
            val bytes = buildThisPleaseJson.encodeToString(StoredRequestSnapshot(1, current.installationId, current.keyId, value)).toByteArray()
            if (bytes.size > MAX_BYTES) { file.delete(); return@disk }
            directory.mkdirs()
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (failure: Throwable) { file.failWrite(stream); throw failure }
            // Bound all account/project files together, including abandoned scopes.
            val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
            var size = files.sumOf { it.length() }
            for (old in files) {
                if (size <= MAX_BYTES) break
                if (old != file.baseFile) { size -= old.length(); old.delete() }
            }
        }
    }

    private suspend fun <T> disk(block: () -> T): T? = withContext(diskDispatcher) {
        // Cache failures must never turn a successful network operation into an error.
        try { block() } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
        catch (_: kotlinx.serialization.SerializationException) { null } catch (_: IllegalArgumentException) { null }
    }

    companion object { internal const val MAX_BYTES = 8L * 1024 * 1024; internal const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}

@Serializable private data class StoredRequestSnapshot(val version: Int, val installationId: String, val keyId: String, val snapshot: RequestSnapshot)

private fun RequestSnapshot.unexpired(now: Long): RequestSnapshot? {
    fun CachedRequestList?.retained() = this?.takeIf { now - it.updatedAt in 0..RequestCache.RETENTION_MILLIS }
    return copy(requests = requests.retained(), mine = mine.retained(), implemented = implemented.retained())
        .takeIf { it.requests != null || it.mine != null || it.implemented != null }
}

private fun RequestSnapshot.withList(section: RequestList, list: CachedRequestList) = when (section) {
    RequestList.REQUESTS -> copy(requests = list)
    RequestList.MINE -> copy(mine = list)
    RequestList.IMPLEMENTED -> copy(implemented = list)
}

private fun merge(items: List<Ticket>, section: RequestList, updates: Collection<TicketChange>): List<Ticket> {
    var result = items
    for (change in updates.sortedBy(TicketChange::revision)) {
        val ticket = change.ticket
        val index = result.indexOfFirst { change.matches(it.id) }
        val belongs = when (section) {
            RequestList.REQUESTS -> ticket.status in setOf(TicketStatus.IN_REVIEW, TicketStatus.PLANNED, TicketStatus.IN_PROGRESS)
            RequestList.MINE -> index >= 0 || change.mine
            RequestList.IMPLEMENTED -> ticket.status == TicketStatus.IMPLEMENTED
        }
        val remaining = result.filterNot { change.matches(it.id) }
        result = if (!belongs) remaining
        else remaining.toMutableList().apply { add(if (index < 0) 0 else index.coerceAtMost(size), ticket) }
    }
    return result
}

internal suspend fun BuildThisPleaseClientProtocol.preloadRequestLists(snapshot: RequestSnapshot?) =
    preloadRequestLists(snapshot) { section ->
        when (section) { RequestList.REQUESTS -> requests(); RequestList.MINE -> myRequests(); RequestList.IMPLEMENTED -> implementedRequests() }
    }

internal suspend fun BuildThisPleaseClientProtocol.preloadRequestLists(
    snapshot: RequestSnapshot?,
    load: suspend (RequestList) -> List<Ticket>,
) = supervisorScope {
    val loads = listOf(
        async { if (snapshot?.requests?.isFresh() != true) load(RequestList.REQUESTS) },
        async { if (snapshot?.mine?.isFresh() != true) load(RequestList.MINE) },
        async { if (snapshot?.implemented?.isFresh() != true) load(RequestList.IMPLEMENTED) },
    )
    var failure: Throwable? = null
    for (load in loads) {
        try { load.await() } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (failure == null) failure = error
        }
    }
    failure?.let { throw it }
}
