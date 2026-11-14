# Repository guidance

- Preserve the split between `buildthisplease-core`, `buildthisplease-compose`, and `sample`.
- Never commit credentials. A `btp_pk_…` value is publishable; Google service-account keys and release signing credentials are not.
- Production mutations must remain bound to method, path, exact body bytes, a one-time Play Integrity request hash, and the installation key signature.
- Do not introduce emulator or sideload integrity bypasses into the production client. Use `MockBuildThisPleaseClient` for development.
- Keep minSdk 24. For SDK, sample, or build-configuration changes, verify `:buildthisplease-core:testDebugUnitTest` plus `:sample:assembleDebug`; prose-only changes need content/link checks instead.
- Request startup loading belongs to the host's retained account client and host-owned CoroutineScope. Never start unowned work from a client initializer. Keep the integration examples in `docs/RequestCaching.md` current, including startup, foreground and account-switch cancellation/clearing.
- Request cache snapshots distinguish absent, empty, and partial lists. Preserve per-list freshness, installation/account isolation, seven-day expiry, the 8 MiB disk bound and generation/mutation barriers. Explicit list getters and pull refresh must remain network-fresh; startup preload may reuse complete lists for 60 seconds.

Issue reporting is open to public contributors through this repository's GitHub Issues. Describe the SDK version, platform, reproduction steps, expected and actual behavior, and sanitized logs; omit credentials and private customer or company material. A local draft containing those details is sufficient when GitHub access is unavailable.

Authorized maintainers with existing private app Brain access follow the [app-owned checklist](https://github.com/keinois/build-this-please-brain/blob/main/Workflows/Development.md#create-and-maintain-issues) for Project membership, assignment, Status, Version and saved-field verification. Public contributors do not need private Brain access and are not responsible for maintaining those fields. Maintainers handle that triage; do not copy private checklist content into public issues or expand access. Routine SDK builds and integration work can proceed without private Project maintenance. Existing technical and action-specific approval instructions still apply.

When you work on a ticket and Rodrigo asks for a PR or a merge into main (or something similar), also update the ticket: follow the app-owned [PR and merge step](https://github.com/keinois/build-this-please-brain/blob/main/Workflows/Development.md#pr-and-merge) (`Closes #n`, issue comment with the PR and verification, Status to review; on merge Done, Version and closing note, #7 dates, read-back).

**New issue intake (Rodrigo, 2026-11-14).** New issues added to an app board in **Triage** have no `Version` unless the user explicitly asks for one. Do not choose the highest backlog/planned version, infer a target from a parent, dates or the current release, or attach a version-derived release parent without that request. Preserve existing issues and their saved versions/parents; this rule does not authorize clearing or changing them. When a Version is explicitly assigned, the existing Version-to-parent sync may align its mapped release parent.
