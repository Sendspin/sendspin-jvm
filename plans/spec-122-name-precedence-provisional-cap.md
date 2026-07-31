# Plan: Discovery name precedence + provisional connection cap (spec PR #122)

## Context

[Spec PR #122](https://github.com/Sendspin/spec/pull/122) (merged) is a large batch of pre-1.0
clarifications to `connection.md`. Most of it is the Noise/PSK/pairing encryption rework, which is
out of scope here — that's tracked separately as its own effort (`claude/encryption-support-plan`
branch, not yet merged). The remaining non-encryption clarifications in that PR are covered across
several separate, independently-scoped changes; this plan covers just two of them — the two
smallest, most self-contained items, picked out for this pass:

1. Discovery-time mDNS `name` is only a hint; the `client/hello`/`server/hello` name is
   authoritative if the two differ.
2. "Clients MAY cap how many provisional connections they hold at once, rejecting further
   incoming connections as if they were lower priority."

Because the encryption/pairing rework hasn't landed on `main` yet, `SendSpinServerHost` here still
uses the pre-encryption `connection_reason: "playback"/"discovery"` admission model (not the
`activities`-based arbitration + `'concurrent_attempt'` reason that PR #122's provisional-cap note
assumes) — the change below is scoped to what actually exists on `main` today, not to the
in-progress encryption branch's arbitration logic.

## Changes

### 1. Discovery name precedence

Audited how sendspin-jvm surfaces names before vs. after a connection completes:

- `SendSpinClient.serverName` (`_serverName` / `serverNameStr`) is set exclusively from
  `server/hello`'s `name` field, in both the client-initiated and server-initiated paths. No
  mDNS-derived name ever touches it — this was already correct, no behavior change needed.
- `DiscoveredServer.name` (`DiscoveryService.kt`) and `NsdServiceEvent.ServiceResolved.name`
  (`NsdBrowser.kt`) had no documentation flagging them as provisional. A host app reading these
  directly had no signal to stop trusting them once a hello is exchanged.

Added KDoc to `DiscoveredServer`, `NsdServiceEvent.ServiceResolved`, and
`ClientAdvertiser.advertise` pointing at `SendSpinClient.serverName` (or `clientName`, for our own
advertised name) as authoritative once connected. Documentation only, no behavior change.

### 2. Provisional connection cap

`SendSpinServerHost` already enforced an implicit, hardcoded cap of exactly **one** pending
(pre-hello) connection: a second connection arriving while one is mid-handshake was rejected
immediately with `client/goodbye` reason `"another_server"` (the same reason used for every other
rejected/displaced connection in this class — this codebase predates the `'concurrent_attempt'`
distinction).

Generalized this into a configurable cap:

- `pendingConn: WebSocket?` / `pendingConnTimeoutJob: Job?` (single-slot) became
  `pendingConns: MutableMap<WebSocket, Job>`, keyed by socket.
- New constructor parameter `maxPendingConnections: Int = MAX_PENDING_CONNECTIONS` (default `4`,
  a small constant — this device is typically a single client and the WIP encryption branch's
  analogous cap for a full handshake settled on 8; 4 is a more conservative default given this
  cheaper cleartext-only handshake still ties up a socket and a scope-`launch`ed timeout per
  pending connection).
- `onOpen` rejects any connection beyond the cap immediately (before sending `client/hello`),
  reusing the existing `"another_server"` goodbye path.
- All the map bookkeeping (`onMessage`, `onClose`, `onError`, `stopServer`, `resolveConnection`,
  `activate`) was updated from single-slot equality checks to map lookups/removals, guarded by the
  existing `pendingLock`.

Two tests added to `SendSpinServerHostTest`: a connection beyond the cap is rejected without
waiting for its hello, and a resolved connection frees its cap slot for the next one.

## Explicitly out of scope

- The `activities`-based arbitration model, `activityRank()`, and the `'concurrent_attempt'` vs
  `'another_server'` goodbye-reason split — these require the Noise/PSK encryption handshake
  (`server/activate` only exists post-handshake in the new model) and are covered by the separate
  encryption-support effort.
- Publishing an actual TXT `name` record from `ClientAdvertiser` (spec: `TXT record: name key
  ... (optional)`). This client currently relies on the mDNS *service instance name* rather than a
  distinct TXT `name` key, and `NsdBrowser`'s `ServiceResolved` doesn't expose TXT attributes at
  all. Fixing that is a larger, separate interface change, not part of this small clarification
  pass.
