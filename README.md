# BuildThisPlease Android SDK

> This branch prepares SDK 0.2.0 (paused). It has no published release tag or artifact yet; test using the local checkout.


Reusable Android client and Jetpack Compose UI for the BuildThisPlease feature-request system. It shares the same projects, tickets, comments, votes, dashboard, and D1 database as the iOS SDK.

The Compose surface mirrors the iOS workflow: counted title and description fields, optional support email, Requests/Mine/Done sections, status-aware voting, implemented release notes, installation-owned comment editing, locked conversations, adaptive large-screen navigation, pull-to-refresh, loading and empty states, and retry-safe mutations.

## Modules

- `buildthisplease-core`: API client, encrypted installation session storage, Android Keystore signing, and Google Play Integrity proofs.
- `buildthisplease-compose`: ready-to-use feature-request board and conversation UI.
- `sample`: runnable mock-backed demonstration; no account or backend is required.
- `buildthisplease-nano`: optional Gemini Nano translation adapter for hosts with minSdk 26+.
- `sample-nano`: separate mock-feedback demonstration of the adapter; checks real device availability.

## Local integration

Add the SDK as a composite build in the consuming app's `settings.gradle.kts`:

```kotlin
includeBuild("../BuildThisPleaseAndroidSDK")
```

Then add:

```kotlin
    implementation("io.github.rosday:buildthisplease-compose:0.2.0")
```

Create one client at application scope and display it from Compose:

```kotlin
val client = BuildThisPleaseClient(
    context = applicationContext,
    configuration = BuildThisPleaseConfiguration(
        baseUrl = "https://feedback.keinois.com",
        projectKey = "btp_pk_your_publishable_project_key",
        environment = BuildThisPleaseConfiguration.Environment.PRODUCTION,
    ),
)

BuildThisPleaseFeedback(client = client, onBack = { /* navigate back */ })
```

`BuildThisPleaseFeedback` is the standalone surface and owns its list, create, and detail navigation. Apps that already use Navigation Compose can instead place the route-specific surfaces in their own graph:

The standalone surface can also hand just those two route decisions to its host by setting
`onOpenRequest` and/or `onCreateRequest`; omitted callbacks keep the built-in navigation.

```kotlin
BuildThisPleaseFeedbackList(
    client = client,
    stateKey = "feedback",
    onOpenTicket = { ticketId -> navController.navigate("feedback/$ticketId") },
    onCreateRequest = { navController.navigate("feedback/new") },
)

BuildThisPleaseTicketDetail(
    client = client,
    ticketId = ticketId,
    stateKey = "feedback",
    onBack = navController::popBackStack,
)

BuildThisPleaseCreateRequest(
    client = client,
    stateKey = "feedback",
    onBack = navController::popBackStack,
    onCreated = navController::popBackStack,
)
```

Use a stable, unique `stateKey` for each feedback project. The SDK retains loaded state across configuration changes and restores the selected section and request after process recreation.

Create one long-lived client per project. Update that retained client when optional subscription or support identity changes. The request form also allows a user to provide an optional support email; the SDK normalizes it and sends it only when entered.

The `btp_pk_…` project key is publishable identification, not an administrator secret. Do not place a Google service-account key, dashboard token, or Cloudflare credential in an Android app.

## Localization

The Compose UI ships Android resources for the same 32 non-English locales as the iOS SDK: Arabic, Czech, Danish, Dutch, Filipino, Finnish, French, German, Greek, Hebrew, Croatian, Hungarian, Indonesian, Italian, Japanese, Korean, Malay, Norwegian Bokmål, Polish, Portuguese (Brazil and Portugal), Romanian, Russian, Slovak, Spanish, Swedish, Thai, Turkish, Ukrainian, Vietnamese, and Simplified and Traditional Chinese.

Android selects these resources from the device or app locale automatically. To expose the languages in Android 13+ per-app language settings, enable AGP's generated locale configuration in the consuming application module:

```kotlin
android {
    androidResources {
        generateLocaleConfig = true
    }
}
```

Also add `src/main/res/resources.properties` to that application module:

```properties
unqualifiedResLocale=en
```

The sample enables this configuration. The SDK does not change the host application's locale or require an in-app language picker. Shared translations can be refreshed with `python3 scripts/import_ios_localizations.py ../BuildThisPleaseSDK`; native-speaker review should remain part of release QA.

## Optional on-device translation with Gemini Nano

Core, Compose, and the original sample retain minSdk 24. The optional Nano artifact requires
minSdk 26 because Google's Prompt API dependency requires it. Android version alone does not
establish eligibility: the device must have a supported AICore model already installed and ready.

For local composite-build integration, replace the Compose dependency with:

```kotlin
implementation("io.github.rosday:buildthisplease-nano:0.2.0")
```

The new artifact is available from this checkout; it will be available on Maven Central only after
the next SDK release. It includes Compose transitively. Retain one provider alongside your client:

```kotlin
val translations = NanoTranslationProvider(
    cacheNamespace = projectKey, // the same publishable project key used by this client
    supportedLanguages = setOf("en", "es"), // source and target languages evaluated for your app
)

BuildThisPleaseFeedback(client = client, translationProvider = translations)
```

Pass the same provider to `BuildThisPleaseFeedbackList` and `BuildThisPleaseTicketDetail` when the
host owns navigation. The host owns the provider; call `translations.close()` only after its
feedback destinations have been disposed. Use a different namespace/provider for each project.

The provider uses Nano only when `checkStatus()` returns `AVAILABLE`. `DOWNLOADABLE`, `DOWNLOADING`,
`UNAVAILABLE`, readiness errors, and readiness timeouts leave original text visible. The SDK never
calls the model download or warmup APIs, requests installation, offers a download prompt, or uses a
cloud translation fallback. AICore may independently manage or update its shared system models.

While the feedback screen is resumed, ticket titles, descriptions, implementation notes, and
non-hidden messages are translated into the app's active language. Editing and sending retain the
original text. Changed results display “Translated · AI” with “Show original” / “Show translation”
controls. Empty, uncertain, same-language, and unsupported fields retain originals. Malformed,
truncated, or failed results are not displayed; recoverable failures offer “Try again”.

Use BCP-47 tags in `supportedLanguages`. A base tag such as `es` covers regional variants while the
requested region is retained in the prompt. Use `zh-Hans` and `zh-Hant` explicitly for Chinese
scripts. Explicit regions, variants, and extensions restrict eligibility to matching tags.
This allowlist is a host evaluation policy, not a guarantee of model quality. Evaluate
source/target pairs on the Nano versions your users run before expanding it. Google's Prompt API
is beta and model output varies across devices: see [prompt evaluation](https://developers.google.com/ml-kit/genai/prompt/android/evaluate-prompt).

One inference runs at a time per provider, with duplicate requests sharing results. Backgrounding
the screen cancels its work and hides translations; eligibility is rechecked on resume and before
each request. Temporary busy errors receive two bounded retries. Quota exhaustion stops automatic
work across all active scopes sharing the provider until a later resume. Oversized content is split at paragraph/word boundaries and displayed
only after every part succeeds.

Translations share a cache across list/detail screens, partitioned by project namespace, original
content, target locale, model identifier, prompt version, and evaluated language allowlist. The cache expires after 30 days,
retains at most 500 groups, and bounds the disk file to 2 MiB. It stores hashed keys and changed
translations rather than another copy of unchanged originals. Cached translations are also hidden
when the model is unavailable. Cache persistence is best effort.

Build the separate demo with `./gradlew :sample-nano:assembleDebug`. It uses mock feedback and a
demo-only English/Spanish allowlist; it does not weaken Play Integrity or contact the backend.
Without an already-ready model it displays the normal feedback UI. For offline device validation,
prepare the model outside this SDK before disconnecting the network; the SDK itself never installs it.

## Google Play setup

Production apps must be recognized by Google Play Integrity. For each distinct Play package:

1. Link the Play Console app to the Google Cloud project used for Play Integrity.
2. Enable the Play Integrity API and grant the backend service account permission to decode tokens.
3. Register the package name, Play App Signing SHA-256 certificate, Cloud project number, and environment in BuildThisPlease.
4. Upload the build to a Play testing or production track. A locally sideloaded production APK is not `PLAY_RECOGNIZED` and normally is not `LICENSED`.

The Cloud project number is returned by the backend challenge. A consuming app does not embed a service-account credential and does not need custom token-verification code.

## Trying the sample

Set `sdk.dir` in `local.properties`, then run:

```shell
./gradlew :sample:assembleDebug
```

The sample starts in mock mode and includes developer controls for mock scenarios, subscription state, appearance, data reset, and staging/production configuration. Supply optional local Gradle properties to exercise a Play-installed staging or production build:

```properties
BTP_STAGING_BASE_URL=https://staging.example.com
BTP_STAGING_PROJECT_KEY=btp_pk_...
BTP_PRODUCTION_BASE_URL=https://feedback.example.com
BTP_PRODUCTION_PROJECT_KEY=btp_pk_...
```

These publishable project keys may be local build configuration; Play Integrity service-account and signing credentials must never be placed here. Mock mode proves integration and UI without weakening production integrity requirements.

The mock supports normal, empty, loading, offline, rate-limited, expired-session, and server-error scenarios for previews and automated tests.

## Privacy and Play Data Safety

The SDK does not request contacts, advertising identifiers, location, or media permissions. It sends the app package/signing identity, app and OS versions, an installation identifier, feature requests, votes, and conversation messages. A host may additionally provide subscription state, RevenueCat App User ID, or support email. Declare the data your app actually supplies in Google Play Data Safety and in the host app's privacy policy. Do not provide optional identity data without an appropriate user-facing purpose and legal basis.

When the optional Nano adapter is included, inference input and output remain on-device. ML Kit
also collects diagnostic/usage metrics, including app/device information, identifiers, configured
languages, input/output sizes, performance, and error information. Account for this optional
dependency in the host app's disclosures: [ML Kit privacy](https://developers.google.com/ml-kit/terms)
and [Google Play data disclosure guidance](https://developers.google.com/ml-kit/android-data-disclosure).

## Distribution

The SDK is released under the MIT License. Tagged releases are published to Maven Central as `io.github.rosday:buildthisplease-core` and `io.github.rosday:buildthisplease-compose`. The local composite-build integration remains available for SDK development.

See [SECURITY.md](SECURITY.md) for the trust model and operational cautions.

Each submitted request includes the host app version as private backend metadata. The version is captured from the SDK configuration (which defaults to the host app version) and is not included in public request responses.

## Request loading and caching (0.2.0)

Create and retain the account's `BuildThisPleaseClient` when the host app opens, then launch `client.preloadRequests()` from a host-owned coroutine scope without blocking startup. Use that same client for `BuildThisPleaseFeedback`. Call preload again when the app returns to the foreground: complete lists fetched in the last 60 seconds are reused, and concurrent startup/screen reads share each endpoint request. The SDK starts no networking in its initializer and installs no background refresh job.

Compose displays the disk snapshot before fetching stale or missing sections, publishes each successful section immediately, and keeps cached lists visible during refresh or offline failures. A successful empty response removes old items. Explicit `requests()`, `myRequests()`, `implementedRequests()` and pull-to-refresh fetch current server data. Successful votes, creation and detail loads update cached lists; changes from the dashboard appear on a later refresh or preload, without a real-time connection.

`cachedRequests()` reads an optional `RequestSnapshot` without networking. Each nullable `CachedRequestList` contains `items`, `updatedAt` (epoch milliseconds), `isComplete`, and `isFresh()`. Null means not loaded/expired; an empty list means successfully loaded empty. A newly created Mine item can appear in a partial list (`isComplete = false`) until its first complete refresh. The cache retains each list for seven days, uses atomic best-effort writes in app-private `cacheDir` (excluded from backups), and bounds request cache files together to 8 MiB. Android can evict these files at any time. The cache stores request fields; it excludes credentials, RevenueCat IDs, support-email metadata and notification preferences.

On logout, first dispose the previous account's feedback UI and cancel and join its host jobs. Await `clearRequestCache()` before releasing that client, then create the next client with a stable account-specific `identityNamespace`. Use a separate stable signed-out namespace. The cache includes the API URL, environment, project, package, namespace and installation/key identity in its scope. Changing email/RevenueCat metadata does not change accounts; `updateUserIdentity()` is not an account-switch API. See [the compiling startup, foreground and account-switch examples](docs/RequestCaching.md). Custom/mock clients remain source-compatible through protocol defaults; `MockBuildThisPleaseClient` keeps an in-memory snapshot.

## Completion updates (0.2.0)

The feedback board includes an Updates destination with unread history, email verification preferences and optional local alerts. Hosts can navigate directly to `BuildThisPleaseUpdates(client, onBack, onOpenTicket)`. Each request starts with one permanent author vote; follow/mute is independent.

Retain a `BuildThisPleaseNotificationSync(applicationContext, client, namespace)` per project/account. Call `synchronize()` on app activation for the unread count, and `synchronize(inBackground = true)` from your host-owned WorkManager worker to post new unread local alerts. The SDK does not install a Worker or choose a background schedule for the host. WorkManager execution is OS-controlled. Android 13+ requires POST_NOTIFICATIONS authorization; the preference screen requests it only after the user enables local alerts. The SDK provides a dedicated notification channel and small icon. The notification intent contains `buildThisPleaseTicketId` and `buildThisPleaseNotificationId`; route these through your existing launch-intent handler. Email app links use the same ticket ID query parameter on the dashboard-configured HTTPS URL.

Email uses Cloudflare and is enabled only after the user confirms the emailed form. In-app updates require no email or RevenueCat. Verification links can connect inboxes using that email within the same project; private request/comment ownership and installation keys remain separate.

For host-authenticated cross-device inboxes, set a stable per-account `identityNamespace` in configuration, obtain `client.installationIdentifier()`, ask your authenticated backend for an installation-bound proof and call `client.linkUserIdentity(token)`. A raw RevenueCat App User ID is never enough to recover another installation's notifications. The optional Worker secret `IDENTITY_SIGNING_KEYS` maps project IDs to per-project random HS256 secrets of 32+ characters, held only by the Worker and that project's host backend. Claims: issuer `buildthisplease-host`, audience the BuildThisPlease project ID, subject the stable RevenueCat/host ID, `installationId`, `iat`, `exp`, lifetime <=300 seconds. The host backend authenticates its user and derives the subject itself.

On account switching, dispose the previous feedback UI and synchronizer and create a client with the next account namespace. Retained Compose ViewModels are keyed by `client.identityScopeKey` plus the host `stateKey`; the concrete client derives that scope from the project and account namespace. Custom client implementations must change `identityScopeKey` when switching accounts. Pass the same `stateKey` to list/detail/create destinations, clear the host navigation stack on sign-out, and cancel background work for the previous account. Keep a separate stable namespace for signed-out usage. Do not reuse notification caches across users. Anonymous RevenueCat IDs can change after reinstall. Deploy the shared 0.2.0 backend with migrations through 0017 before releasing the SDKs; local builds do not verify live Play Integrity, email delivery or background timing.
