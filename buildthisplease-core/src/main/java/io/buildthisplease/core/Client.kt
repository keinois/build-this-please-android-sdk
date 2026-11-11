package io.buildthisplease.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BuildThisPleaseClient(
    context: Context,
    configuration: BuildThisPleaseConfiguration,
    private val transport: BuildThisPleaseTransport = KtorBuildThisPleaseTransport(),
    private val integrity: IntegrityTokenProvider = GooglePlayIntegrityTokenProvider(context),
    signer: InstallationSigner? = null,
    credentialStore: CredentialStore? = null,
) : BuildThisPleaseClientProtocol {
    private val appContext = context.applicationContext
    private var config = configuration.resolve(appContext)
    private val prefix = installationStoragePrefix(this.config)
    private val requestCacheScope = requestCacheStorageScope(this.config)
    override val identityScopeKey: String get() = prefix
    private val signer = signer ?: AndroidKeystoreInstallationSigner("btp.$prefix.installation")
    private val credentials = credentialStore ?: EncryptedNoBackupCredentialStore(
        appContext,
        "btp.$prefix.storage",
        "$prefix.credentials",
    )
    private val sessionMutex = Mutex()
    private var memorySession: StoredSession? = null
    private var lastKnownInstallationId: String? = null
    private val json = buildThisPleaseJson
    private val requestCache = RequestCache(File(appContext.cacheDir, "buildthisplease/requests"), requestCacheScope)
    // Fixed stripes bound storage while keeping list downloads independent of ticket writes.
    private val ticketMutationMutexes = Array(32) { Mutex() }

    override suspend fun cachedRequests(): RequestSnapshot? = requestCache.snapshot(localCacheIdentity())
    override suspend fun preloadRequests() {
        val barrier = requestCache.clearBarrier()
        preloadRequestLists(cachedRequests()) { section -> requestList(section, section.path, barrier) }
    }
    override suspend fun clearRequestCache() { requestCache.clear() }

    private suspend fun localCacheIdentity(): RequestCacheIdentity? {
        if (!signer.hasKey()) return null
        val session = memorySession ?: credentials.readSession() ?: return null
        return RequestCacheIdentity(session.installationId, signer.keyId)
    }

    private suspend fun requestList(section: RequestList, path: String, clearBarrier: Long? = null): List<Ticket> {
        val barrier = clearBarrier ?: requestCache.clearBarrier()
        return requestCache.fetch(section, localCacheIdentity(), ::localCacheIdentity, barrier) { get(path, TicketPage.serializer()).items }
    }

    override suspend fun configuration(): ProjectConfiguration = get("/v1/config", ProjectConfiguration.serializer())
    override suspend fun requests(): List<Ticket> = requestList(RequestList.REQUESTS, "/v1/tickets?limit=100")
    override suspend fun myRequests(): List<Ticket> = requestList(RequestList.MINE, "/v1/me/tickets?limit=100")
    override suspend fun implementedRequests(): List<Ticket> = requestList(RequestList.IMPLEMENTED, "/v1/implemented?limit=100")
    override suspend fun ticket(id: String): Ticket = requestCache.ticket(::localCacheIdentity, requestedId = id) {
        get("/v1/tickets/${id.pathComponent()}", TicketEnvelope.serializer()).ticket
    }
    override suspend fun comments(ticketId: String): List<Comment> =
        get("/v1/tickets/${ticketId.pathComponent()}/comments", CommentPage.serializer()).items

    override suspend fun createTicket(title: String, description: String, email: String?, idempotencyKey: String): Ticket {
        val operation = requestCache.operation(retainTicketChanges = true)
        try {
            val body = TicketInput(title.trim(), description.trim(), email.normalized()?.lowercase(), config.appVersion.normalized())
            val ticket = mutate("POST", "/v1/tickets", body, TicketInput.serializer(), TicketEnvelope.serializer(), idempotencyKey).ticket
            return requestCache.update(ticket, true, operation, localCacheIdentity())
        } finally { requestCache.finishTicketOperation() }
    }

    override suspend fun setVote(ticketId: String, voted: Boolean): Ticket {
        // Capture the clear barrier before waiting; queued calls cannot repopulate a cleared cache.
        val operation = requestCache.operation()
        return ticketMutationMutexes[(ticketId.hashCode() and Int.MAX_VALUE) % ticketMutationMutexes.size].withLock {
            val ticket = mutateRaw(
                if (voted) "PUT" else "DELETE",
                "/v1/tickets/${ticketId.pathComponent()}/vote",
                ByteArray(0),
                TicketEnvelope.serializer(),
            ).ticket
            requestCache.update(ticket, false, operation, localCacheIdentity(), requestedId = ticketId)
        }
    }

    override suspend fun createComment(ticketId: String, body: String, idempotencyKey: String): Comment = mutate(
        "POST",
        "/v1/tickets/${ticketId.pathComponent()}/comments",
        CommentInput(body.trim()),
        CommentInput.serializer(),
        CommentEnvelope.serializer(),
        idempotencyKey,
    ).comment

    override suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment = mutate(
        "PATCH",
        "/v1/tickets/${ticketId.pathComponent()}/comments/${commentId.pathComponent()}",
        CommentInput(body.trim()),
        CommentInput.serializer(),
        CommentEnvelope.serializer(),
    ).comment

    override suspend fun updateSubscriptionStatus(status: SubscriptionStatus) {
        mutate("PATCH", "/v1/installations/me/subscription-status", SubscriptionInput(status, isoNow()), SubscriptionInput.serializer(), UnitEnvelope.serializer())
        config = config.copy(subscriptionStatus = status)
    }

    override suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String?) {
        val body = IdentityInput(revenueCatAppUserId.normalized(), email.normalized()?.lowercase())
        mutate("PATCH", "/v1/installations/me/user-identity", body, IdentityInput.serializer(), UnitEnvelope.serializer())
        config = config.copy(revenueCatAppUserId = body.revenueCatAppUserId, userEmail = body.userEmail)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        return decode(authenticatedResponse { session -> send("GET", path, null, session.token) }, serializer)
    }

    private suspend fun <B, T> mutate(
        method: String,
        path: String,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<T>,
        idempotencyKey: String? = null,
    ): T = mutateRaw(method, path, json.encodeToString(bodySerializer, body).toByteArray(), responseSerializer, idempotencyKey)

    private suspend fun <T> mutateRaw(
        method: String,
        path: String,
        body: ByteArray,
        serializer: KSerializer<T>,
        idempotencyKey: String? = null,
    ): T {
        val challengeBody = AssertionChallengeInput(method, path, body.sha256Base64Url())
        val challengeBytes = json.encodeToString(AssertionChallengeInput.serializer(), challengeBody).toByteArray()
        val challenge = decode(
            authenticatedResponse { session ->
                send("POST", "/v1/android/integrity/assertions/challenges", challengeBytes, session.token)
            },
            IntegrityChallenge.serializer(),
        )
        val session = validSession()
        val proofHeaders = mapOf(
            "X-BTP-Android-Integrity-Challenge-ID" to challenge.challengeId,
            "X-BTP-Android-Integrity-Token" to integrity.token(challenge.cloudProjectNumber.toLong(), challenge.requestHash),
            "X-BTP-Android-Key-Signature" to encodeBase64Url(signer.sign(challenge.requestHash)),
        ) + (idempotencyKey?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())
        return decode(send(method, path, body, session.token, proofHeaders), serializer)
    }

    private suspend fun authenticatedResponse(block: suspend (StoredSession) -> TransportResponse): TransportResponse {
        var session = validSession()
        var response = block(session)
        if (response.isInvalidSession()) {
            sessionMutex.withLock {
                memorySession = null
                credentials.clearSession()
            }
            session = validSession()
            response = block(session)
        }
        return response
    }

    private suspend fun validSession(): StoredSession = sessionMutex.withLock {
        memorySession?.takeIf { it.isUsable() }?.let { return@withLock it }
        credentials.readSession()?.takeIf { it.isUsable() }?.let {
            memorySession = it
            lastKnownInstallationId = it.installationId
            return@withLock it
        }
        val previousId = lastKnownInstallationId ?: credentials.readSession()?.installationId
        val established = establishSession()
        if (previousId != null && previousId != established.installationId) requestCache.clear(installationReplacement = true)
        credentials.writeSession(established)
        memorySession = established
        lastKnownInstallationId = established.installationId
        established
    }

    private suspend fun establishSession(): StoredSession {
        if (signer.hasKey()) {
            try {
                return recoverSession()
            } catch (failure: BuildThisPleaseException.Server) {
                if (failure.code != "android_key_not_registered") throw failure
                requestCache.clear(installationReplacement = true)
                signer.reset()
                credentials.clearSession()
            }
        }
        val publicKey = signer.publicKeySpki()
        val challengeInput = RegistrationChallengeInput(
            packageName = requireNotNull(config.packageName),
            certificateSha256 = signingCertificateSha256(appContext),
            environment = config.environment.wireValue,
            keyId = signer.keyId,
            publicKeySpki = encodeBase64Url(publicKey),
            appVersion = config.appVersion,
            osVersion = config.osVersion,
            subscriptionStatus = config.subscriptionStatus,
            revenueCatAppUserId = config.revenueCatAppUserId,
            userEmail = config.userEmail,
        )
        val bytes = json.encodeToString(RegistrationChallengeInput.serializer(), challengeInput).toByteArray()
        val challenge = decode(send("POST", "/v1/android/integrity/challenges", bytes), IntegrityChallenge.serializer())
        return completePublicProof("/v1/android/integrity/registrations", challenge)
    }

    private suspend fun recoverSession(): StoredSession {
        val input = RecoveryChallengeInput(
            keyId = signer.keyId,
            packageName = requireNotNull(config.packageName),
            certificateSha256 = signingCertificateSha256(appContext),
            environment = config.environment.wireValue,
        )
        val bytes = json.encodeToString(RecoveryChallengeInput.serializer(), input).toByteArray()
        val challenge = decode(send("POST", "/v1/android/integrity/recovery/challenges", bytes), IntegrityChallenge.serializer())
        return completePublicProof("/v1/android/integrity/recoveries", challenge)
    }

    private suspend fun completePublicProof(path: String, challenge: IntegrityChallenge): StoredSession {
        val proof = IntegrityProof(
            challenge.challengeId,
            integrity.token(challenge.cloudProjectNumber.toLong(), challenge.requestHash),
            encodeBase64Url(signer.sign(challenge.requestHash)),
        )
        val bytes = json.encodeToString(IntegrityProof.serializer(), proof).toByteArray()
        val response = decode(send("POST", path, bytes), SessionEnvelope.serializer())
        return StoredSession(response.sessionToken, response.installationId, response.expiresAt)
    }

    private suspend fun send(
        method: String,
        path: String,
        body: ByteArray?,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): TransportResponse = transport.send(
        method,
        config.baseUrl + path,
        body,
        mapOf("X-BuildThisPlease-Project-Key" to config.projectKey) +
            (token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()) + extraHeaders,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> decode(response: TransportResponse, serializer: KSerializer<T>): T {
        if (response.status !in 200..299) {
            val error = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), response.body.decodeToString()) }.getOrNull()
            throw BuildThisPleaseException.Server(error?.error?.code ?: "request_failed", error?.error?.message ?: "Request failed.", response.status)
        }
        if (serializer == UnitEnvelope.serializer() && response.body.isEmpty()) return UnitEnvelope() as T
        return try { json.decodeFromString(serializer, response.body.decodeToString()) }
        catch (failure: Throwable) { throw BuildThisPleaseException.InvalidResponse(failure) }
    }

    private fun TransportResponse.isInvalidSession(): Boolean {
        if (status != 401) return false
        return runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), body.decodeToString()).error.code
        }.getOrNull() in setOf("installation_session_required", "installation_session_invalid")
    }
}

@Serializable private data class TicketPage(val items: List<Ticket>)
@Serializable private data class CommentPage(val items: List<Comment>)
internal val buildThisPleaseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable internal data class TicketInput(val title: String, val description: String, val email: String? = null, val appVersion: String? = null)
@Serializable private data class CommentInput(val body: String)
@Serializable private data class SubscriptionInput(val status: SubscriptionStatus, val observedAt: String)
@Serializable private data class IdentityInput(val revenueCatAppUserId: String? = null, val userEmail: String? = null)
@Serializable private data class AssertionChallengeInput(val method: String, val path: String, val bodyHash: String)
@Serializable internal data class RegistrationChallengeInput(
    val packageName: String,
    val certificateSha256: String,
    val environment: String,
    val keyId: String,
    val publicKeySpki: String,
    val appVersion: String? = null,
    val osVersion: String,
    val subscriptionStatus: SubscriptionStatus,
    val revenueCatAppUserId: String? = null,
    val userEmail: String? = null,
)
@Serializable private data class RecoveryChallengeInput(
    val keyId: String,
    val packageName: String,
    val certificateSha256: String,
    val environment: String,
)
@Serializable private data class IntegrityProof(val challengeId: String, val integrityToken: String, val keySignature: String)
@Serializable private data class IntegrityChallenge(val challengeId: String, val requestHash: String, val cloudProjectNumber: String, val expiresAt: String)
@Serializable private data class SessionEnvelope(val sessionToken: String, val installationId: String, val expiresAt: String)
@Serializable private data class ErrorEnvelope(val error: ErrorBody)
@Serializable private data class ErrorBody(val code: String, val message: String)
@Serializable private class UnitEnvelope

private fun StoredSession.isUsable(): Boolean = parseIso(expiresAt)?.time?.minus(System.currentTimeMillis()) ?: 0L > 60_000
private fun parseIso(value: String): Date? = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
    runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value) }.getOrNull()
}
private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
private fun String.pathComponent(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

// Keep existing installation keys/credentials discoverable across SDK upgrades.
internal fun installationStoragePrefix(config: BuildThisPleaseConfiguration): String =
    (config.baseUrl + "\n" + config.projectKey + (config.identityNamespace?.let { "\n" + it } ?: "")).sha256Base64Url().take(18)

internal fun requestCacheStorageScope(config: BuildThisPleaseConfiguration): String =
    buildThisPleaseJson.encodeToString(listOf(config.baseUrl, config.environment.wireValue, config.projectKey, config.packageName, config.identityNamespace)).sha256Base64Url()

private val RequestList.path: String get() = when (this) {
    RequestList.REQUESTS -> "/v1/tickets?limit=100"
    RequestList.MINE -> "/v1/me/tickets?limit=100"
    RequestList.IMPLEMENTED -> "/v1/implemented?limit=100"
}

@Suppress("DEPRECATION")
private fun signingCertificateSha256(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        requireNotNull(info.signingInfo).apkContentsSigners.first()
    } else {
        requireNotNull(info.signatures).first()
    }
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
