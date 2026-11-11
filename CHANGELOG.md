# Changelog

## 0.2.0 (unreleased, paused)

- Explicit host startup/foreground request preloading, account-scoped disk snapshots and immediate Compose hydration; cached lists remain visible offline while refreshed sections arrive independently.
- Shared in-flight list reads, 60-second preload freshness, seven-day retention, bounded atomic cache storage, logout clearing, and race-safe vote/create/detail updates.
- Completion-only Updates inbox with unread state, pagination and request navigation.
- Optional verified completion emails delivered by Cloudflare Email Service.
- Request follow/mute controls independent of votes; automatic permanent author vote.
- Optional local-notification synchronization for host-owned background scheduling.
- Verified notification identity linking and account-scoped credential namespaces.
- Deploy the shared BuildThisPlease 0.2.0 backend through migration 0017 before releasing the SDKs; completion updates use migration 0016 and iOS recovery uses 0017. Existing installations continue anonymously; support emails are not automatically subscribed.
