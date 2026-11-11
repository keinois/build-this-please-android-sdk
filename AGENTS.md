# Repository guidance

- Preserve the split between `buildthisplease-core`, `buildthisplease-compose`, and `sample`.
- Never commit credentials. A `btp_pk_…` value is publishable; Google service-account keys and release signing credentials are not.
- Production mutations must remain bound to method, path, exact body bytes, a one-time Play Integrity request hash, and the installation key signature.
- Do not introduce emulator or sideload integrity bypasses into the production client. Use `MockBuildThisPleaseClient` for development.
- Keep minSdk 24. For SDK, sample, or build-configuration changes, verify `:buildthisplease-core:testDebugUnitTest` plus `:sample:assembleDebug`; prose-only changes need content/link checks instead.
- Request startup loading belongs to the host's retained account client and host-owned CoroutineScope. Never start unowned work from a client initializer. Keep the integration examples in `docs/RequestCaching.md` current, including startup, foreground and account-switch cancellation/clearing.
- Request cache snapshots distinguish absent, empty, and partial lists. Preserve per-list freshness, installation/account isolation, seven-day expiry, the 8 MiB disk bound and generation/mutation barriers. Explicit list getters and pull refresh must remain network-fresh; startup preload may reuse complete lists for 60 seconds.
