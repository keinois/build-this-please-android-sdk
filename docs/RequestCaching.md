# Load requests when the host app opens

Retain one client per project, environment and signed-in account. Creating it performs no networking. Start `preloadRequests()` when the app opens and again on foreground activation, using a coroutine scope the host owns. The app's first screen can render immediately; opening feedback later uses the same client and cache.

This account container uses only Android, SDK and Kotlin coroutines APIs. Supply configuration from the host rather than inferring a production identity from device metadata:

```kotlin
import android.content.Context
import io.buildthisplease.core.BuildThisPleaseClient
import io.buildthisplease.core.BuildThisPleaseConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class FeedbackSession(context: Context, configuration: BuildThisPleaseConfiguration) {
    val client = BuildThisPleaseClient(context.applicationContext, configuration)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    fun onAppOpenedOrForegrounded() {
        scope.launch {
            try {
                client.preloadRequests()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Startup continues; any retained snapshot remains available offline.
            }
        }
    }

    suspend fun closeForAccountSwitch() {
        job.cancelAndJoin()
        client.clearRequestCache()
    }
}
```

Create this container early in the host's application/account owner and call `onAppOpenedOrForegrounded()` from its existing startup and foreground callbacks. Do not create a new container inside each feedback destination. This minimal application example uses a separate stable signed-out namespace:

```kotlin
import android.app.Application
import io.buildthisplease.core.BuildThisPleaseConfiguration

class ExampleApplication : Application() {
    lateinit var feedback: FeedbackSession
        private set

    override fun onCreate() {
        super.onCreate()
        feedback = FeedbackSession(
            this,
            BuildThisPleaseConfiguration(
                baseUrl = "https://feedback.example.com",
                projectKey = "btp_pk_publishable_project_key",
                environment = BuildThisPleaseConfiguration.Environment.PRODUCTION,
                identityNamespace = "signed-out",
            ),
        )
        feedback.onAppOpenedOrForegrounded()
    }

    // Invoke from the host's existing foreground callback.
    fun onHostForeground() = feedback.onAppOpenedOrForegrounded()

    // Invoke from the host's serialized login/logout flow. Remove the old UI and navigation
    // destinations before this call, and cancel notification/background work for that account.
    suspend fun switchFeedbackAccount(nextConfiguration: BuildThisPleaseConfiguration) {
        feedback.closeForAccountSwitch()
        feedback = FeedbackSession(this, nextConfiguration)
        feedback.onAppOpenedOrForegrounded()
    }
}
```

For signed-in usage, set `identityNamespace` to an opaque stable namespace from the host's authenticated account model; it must change whenever the account changes. Use the same namespace when recreating that account's client on later launches. The client hashes the API URL, environment, project key, package and namespace for storage and verifies the saved installation ID and installation signing key before restoration. Do not use an email or mutable RevenueCat metadata as an account namespace. Calling `updateUserIdentity()` only updates private metadata; it does not switch the client, credential, UI or cache scope.

Pass the retained container's client to the existing UI:

```kotlin
import androidx.compose.runtime.Composable
import io.buildthisplease.compose.BuildThisPleaseFeedback

@Composable
fun HostFeedbackDestination(session: FeedbackSession, onBack: () -> Unit) {
    BuildThisPleaseFeedback(client = session.client, onBack = onBack)
}
```

## Snapshot and refresh behavior

`cachedRequests()` performs local I/O only and returns `RequestSnapshot?`, with independently optional `requests`, `mine` and `implemented` sections. Each section is `CachedRequestList(items, updatedAt, isComplete)`. An absent section has not loaded or has expired; a present empty list is a successful empty response. `updatedAt` is epoch milliseconds. A synthetic Mine list containing a newly created item is partial (`isComplete = false`) until its first full fetch, so it displays immediately while remaining eligible for preload.

`preloadRequests()` fetches stale, partial or missing sections concurrently and preserves successful sections if another endpoint fails. It reuses complete lists for 60 seconds. Simultaneous startup and feedback-screen reads coalesce per endpoint. Callers own cancellation: when the owner of a shared request leaves, an active waiter can start its own replacement request. Explicit `requests()`, `myRequests()` and `implementedRequests()` always fetch the network except when joining an already running request. Pull-to-refresh uses these explicit getters.

Compose hydrates saved sections before networking, shows each refreshed section as soon as it arrives and preserves cached data while loading or offline. Empty successful results remove stale items. Successful vote, create and ticket-detail responses update the snapshot, and delayed reads cannot overwrite newer mutations. `clearRequestCache()` invalidates outstanding cache work, clears memory and disk, and prevents old responses from restoring cleared state. Dispose the old UI and cancel host jobs during account switches as illustrated above.

The SDK stores request fields for up to seven days per section, with an 8 MiB bound across request-cache files, atomic writes and best-effort I/O on `Dispatchers.IO`. Storage is app-private Android `cacheDir`, which is excluded from backup and may be evicted by the OS. Tokens, support emails, RevenueCat metadata, comments and notification preferences are not serialized in this cache. Cache corruption, unavailable storage and oversized snapshots do not fail otherwise successful requests.

There is no server push connection or automatic polling schedule. Dashboard changes and changes from another device appear during a subsequent preload, board refresh, detail fetch or explicit pull refresh. Hosts choose their startup, foreground and any background schedule. The sample preloads while its menu is visible using `LaunchedEffect(client)`; production hosts should use their retained account owner and lifecycle.
