# Changelog

## 0.1.6 (unreleased)

- Preload Requests, Mine and Implemented in host-owned launch and foreground coroutines.
- Persist separate cache-first list snapshots, refresh independently and preserve available content offline.
- Reuse complete snapshots for 60 seconds, retain them for seven days and limit disk storage to 8 MiB.
- Isolate cached requests and retained UI by project, service, installation and optional account namespace.
- Coalesce list requests, update snapshots after mutations and protect newer changes from delayed responses or account clearing.
- Keep restored navigation from overriding later opens, Back, section changes or Create while startup loading finishes.

Completion inboxes, email opt-in, follow/mute controls and local alerts are deferred to the paused 0.2.0 branch.
