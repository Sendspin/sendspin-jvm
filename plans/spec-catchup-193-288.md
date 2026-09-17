# Plan: Catch up on non-encryption spec changes (PRs #193-#288)

## Context

Batch of [Sendspin/spec](https://github.com/Sendspin/spec) PRs merged 2026-09-02 through
2026-09-16 (after our last sync point, PR #192). A parallel session is implementing the
encryption/pairing/management portion of the same window in a sibling worktree (`management/*`
namespace removal, pairing-record model overhaul, dynamic-pairing-code rounds rework,
digit-audio-pack revert, re-handshake changes, activity-set table changes — PRs #183, #189, #192,
#194, #208, #223, #234, #237, #241, #242, #244, #246, #247, #249, #250, #253, #263, #272, #284,
#285, #287). This plan covers everything else from the window: 60 PRs total, checked against the
current codebase.

As with `spec-catchup-160-175.md`, this codebase still has no Noise/encryption layer, no
`client/init`/`server/init`/`server/activate` handshake, and no `source@v1` role — `SendSpinClient`
goes straight from the legacy cleartext `client/hello` ↔ `server/hello` exchange to
`server/hello.active_roles` as the role-activation source of truth. Several PRs in this window are
only reachable once that transport/activation layer exists; those are called out explicitly under
**Explicitly out of scope** rather than implemented against a mechanism that doesn't exist yet.

## Changes

### 1. [PR #188](https://github.com/Sendspin/spec/pull/188) "Split large artwork images across multiple messages"

Full breaking rewrite of the artwork binary protocol, from one binary message per image to an
announce/part/cancel chunking scheme:

- `Messages.kt`: new `ArtworkAnnounce`/`ArtworkPart`/`ArtworkCancel` sealed under
  `ArtworkBinaryMessage`, plus `ARTWORK_FLAG_CANCEL`/`ARTWORK_FLAG_ANNOUNCE`/
  `ARTWORK_FLAG_RESERVED_MASK`/`ARTWORK_MAX_MESSAGE_BYTES` constants.
- `MessageParser.kt`: `parseArtwork()` replaces the old flat `[type][timestamp:8][data]` artwork
  path with `[type][flags][timestamp:8][total_size:4]` (announce, 14 bytes exactly),
  `[type][flags][data]` (part), `[type][flags]` (cancel, 2 bytes exactly). A new
  `MessageParser.Malformed` result carries every message-format violation the spec lists (short/
  oversized frame, wrong announce/cancel length, both flag bits set, a reserved bit set) back to
  the caller, which per spec MUST close the connection rather than just log-and-ignore.
- `SendSpinClient.kt`: per-channel runtime state (`ArtworkChannelRuntime`: current image, pending
  image + timestamp, a cancellable apply job) plus one global `ArtworkTransfer` slot (the spec
  allows at most one in-flight transfer across all four channels). `handleArtworkAnnounce`/
  `handleArtworkPart`/`handleArtworkCancel` implement the announce → parts → complete → scheduled
  → current pipeline, including the malformed-*sequence* errors that aren't parser-detectable
  (announce while a transfer is in flight, a part with no/mismatched transfer, a part that would
  overrun `total_size`) — these close the connection via a new `closeDueToProtocolError()` helper.
  `scheduleArtworkApply()` mirrors the metadata scheduled-update pattern: a past/present timestamp
  (translated via the time filter) applies immediately, a future one schedules a coroutine, and
  artwork is never dropped for lateness (unlike audio).
- Config-change handling: an artwork `stream/start` now diffs the new channel array against the
  previous one and discards each changed channel's pending image (spec: "A stream/start that
  changes a channel's configuration ... discards that channel's pending image"). `stream/end` for
  `artwork` now clears every channel's current *and* pending image and aborts any in-flight
  transfer (previously only cleared the exposed `_albumArtwork`/`_artistArtwork` flows, not the
  chunking state) — see PR #266 below, folded into the same code path.
- Tests: `MessageParserTest` artwork cases rewritten for the new wire format plus new malformed-
  message cases; new `ArtworkChunkingTest.kt` covers announce+parts→current, total_size 0 (clear),
  cancel-discards-pending-not-current, future-timestamp scheduling, all three malformed-sequence
  closes, and stream/end teardown.

### 2. [PR #195](https://github.com/Sendspin/spec/pull/195) "Fold `stream/request-format` into `client/state`" + [PR #177](https://github.com/Sendspin/spec/pull/177) "Allow players to change `supported_commands` mid-session"

Implemented together since both restructure the same `client/hello`/`client/state` boundary and
conflict in the player state object (spec PR #195's own description flags this).

- `stream/request-format` is gone: `StreamRequestFormat`/`StreamRequestFormatPayload`/
  `PlayerFormatRequest` removed, `SendSpinClient.requestPlayerFormat()` replaced by
  `setPlayerFormatPreference(format: AudioFormat?)`, which sets `client/state.player.format` and
  reports it immediately. The old partial-request shape (`PlayerFormatRequest`, all fields
  optional) is gone too — `client/state`'s `format` object must name a complete entry from
  `supported_formats`, which is exactly `AudioFormat`'s shape, so it's reused directly instead of
  introducing a near-duplicate type.
- `PlayerSupport` (`client/hello`) drops `supported_commands`; `PlayerStatePayload`
  (`client/state`) is now the single source of truth for it. `ClientPreferences.
  playerSupportedCommands` default changes from `["volume", "mute"]` (the old hello-only default)
  to `["volume", "mute", "set_output_delay"]` — the union of what used to be split across the two
  messages by default, so out-of-the-box wire behavior for a caller that doesn't override the
  preference is unchanged in substance (same three commands end up advertised, just all in
  `client/state` now instead of split).
- `artwork@v1_support` is removed from `client/hello` entirely. Its channel list
  (`source`/`format?`/`width?`/`height?`) moves to `client/state.artwork.channels`, and the old
  `ArtworkChannel` (hello-only, non-optional `format`/`width`/`height`) and `StreamArtworkChannel`
  (stream/start-only, same fields) collapse into one shared `ArtworkChannelConfig` with nullable
  `format`/`width`/`height` (required only when `source != "none"`, matching the spec's own
  `?`-marked fields) — used identically by `client/state` and `stream/start`.
  `ClientPreferences.artworkChannels` still seeds the initial value but is now runtime-mutable via
  the new `setArtworkChannels()`.
- `VisualizerSupport` (`client/hello`) keeps only `buffer_capacity`; `types`/`rate_max`/`spectrum`
  move to a new `VisualizerStatePayload` in `client/state.visualizer`, seeded from the new
  `ClientPreferences.visualizerRequest` and updatable via `setVisualizerRequest()`. Since
  `rate_max` is a required positive integer in `client/state` even when `types` is empty, an unset
  `visualizerRequest` falls back to `types: []`, `rate_max: 30` rather than omitting the object
  (the object itself is still omitted entirely when the visualizer role isn't active, per the
  general `client/state` role-gating rule).
- `sendClientState()` now builds `artwork`/`visualizer` objects under the same "role must be
  active" gating already used for `player`.
- Tests: `StreamRequestFormatTest.kt` replaced by `PlayerFormatPreferenceTest.kt`.
  `SendSpinClientHelloTest.kt` updated throughout (hello no longer carries artwork channels or
  visualizer types/rate/spectrum; new tests cover the `client/state` side and the two setters).
  Found and fixed a real test-only bug surfaced by this work: `RecordingSocket`/`CapturingSocket`
  used a plain `mutableListOf`, which a background coroutine (clock sync / periodic state reports)
  and the test's own read of `.sent` could touch concurrently — `ConcurrentModificationException`
  under the new tests' heavier use of `.sent`. Switched both to `CopyOnWriteArrayList`.

### 3. [PR #238](https://github.com/Sendspin/spec/pull/238) "Make Opus optional for servers and warn about its license fees"

`ClientPreferences` now validates in `init` that an advertised player lists at least one `flac` or
`pcm` entry in `supportedFormats` — Opus alone is no longer sufficient, since servers are only
required to support `flac`/`pcm` and Opus support is now optional (patent licensing). This is a
real interoperability guard: a host app that only wired up Opus would previously advertise a
player no compliant minimal server could serve.

### 4. [PR #209](https://github.com/Sendspin/spec/pull/209) "Add `client/leave` to replace the `available` flap"

New `ClientLeave`/`ClientLeavePayload` message and `SendSpinClient.leave()`. Replaces the
documented (but never implementable without this message) pattern of toggling `available` to leave
a group without going fully unavailable. New `ClientLeaveTest.kt`.

### 5. [PR #210](https://github.com/Sendspin/spec/pull/210) "Fix metadata progress clock conversion"

`ClockSync.calculateProgress()` computed elapsed time by diffing two *local*-clock timestamps
(`nowMicros - baseLocalTimeMicros`), which the spec now says is wrong under clock drift: the
correct elapsed time is in the *server's* clock domain (`nowServerMicros - metadata.timestamp`).
Added `ClockSync.toServerMicros()` (the exact inverse of the existing `toLocalMicros()`, refactored
to share the offset/drift extrapolation via a new private `currentOffsetMicros()` helper) and
changed `calculateProgress`'s signature from `(baseProgressMs, baseLocalTimeMicros, ..., nowMicros)`
to `(baseProgressMs, baseServerTimeMicros, ..., nowServerMicros)` — callers now pass
`metadata.timestamp` directly and obtain "now" via `clockSync.toServerMicros(ClockSync.localMicros())`
instead of a captured local arrival time. `ClockSyncTest.kt` updated (parameter rename) plus a new
round-trip test for `toServerMicros`.

### 6. [PR #266](https://github.com/Sendspin/spec/pull/266) "Clarify artwork stream lifecycle and data handling"

The "`stream/end` MUST clear the current image as well as discard any pending image" requirement
is implemented as part of PR #188's rewrite (see #1 above) rather than separately, since it only
makes sense against the new pending/current model. The remaining requirements in this PR (process
announces/cancels and count bytes even while "discarding" for unavailability, don't reject binary
data outside a stream by any means other than the sender-side prohibition) are already satisfied:
this client has no "discard while unavailable" branch at all — announces/parts/cancels are always
processed identically regardless of any hypothetical local-unavailability state — and it never had
special-case rejection logic for artwork binary data outside a stream to begin with.

### 7. [PR #283](https://github.com/Sendspin/spec/pull/283) "Clarify buffer handling on `stream/start` messages during an active stream"

No behavioral change needed: `SendSpinClient`'s `is StreamStart ->` branch already calls
`audioPlayer.transition(msg.player)` (not `flush()`+`configure()`) when a stream is already
playing, matching "an in-place `stream/start` updates the configuration without ending the
stream." Expanded `AudioPlayer.transition()`'s doc comment to state the contract explicitly (no
flush, no resend, decode each buffered chunk in the format that was in effect when it arrived) so
platform implementations (sendspin-android-tv etc., which own the actual decoder) don't have to
infer it — the PR's own "breaking changes" list names exactly this class of bug in sibling SDKs.

## No code change needed (verified against the current codebase)

Grouped by why, each individually confirmed by reading the merged spec file plus the relevant
source:

**Pure editorial / wording-only, zero client-visible effect** — #213 (`tracks_downbeats`
conditional: already a defaulted-`false` field, absence already handled), #215, #219, #220, #221,
#207, #218, #214, #216, #217, #222, #224, #231, #251, #252, #254, #255, #256, #258, #262, #267,
#268 (client-visible part is server-side group-volume rounding; the client-side "select commands
from latest supported list" is a server-enforced rule, not a client validation this library needs
to add), #269, #282, #286, #245 (pairing-flow diagram, not implemented here), #239, #240.

**Server-side only — this client is the passive receiver of the behavior, not the actor** — #211,
#227, #228 (`rate_max` is still one wire integer; only its documented meaning changed to "per
periodic type" rather than "total"), #229, #232, #235, #236, #248, #257 (the "at least one
format"/"non-negative timing"/"positive rate cap" constraints are all already satisfied by this
client's defaults and the new PR #238 `init` check), #260, #261, #265 (requires the
`client/init`/`server/init` cleartext handshake this codebase doesn't have — see below), #270, #271
(this client buffers no visualizer data of its own to clear — frames are forwarded via callback
immediately — and never had ambiguous binary-rejection logic to begin with), #288.

**Not applicable — role/mechanism not implemented in this codebase** — #225, #256 (`source@v1` is
not implemented; see `spec-catchup-160-175.md` §4 for the same finding on an earlier PR), #273
(same), #243, #248 (application-specific/custom roles are not a feature this library exposes).

**Not applicable — depends on the encryption/activation layer covered by the parallel session** —
see below.

## Explicitly out of scope

- **The 21 encryption/pairing/management PRs listed in Context** — owned by the parallel session.
- **[PR #275](https://github.com/Sendspin/spec/pull/275) "Clear role state on deactivation"** —
  investigated specifically at the requester's flag. This PR requires clearing a removed role's
  cached `server/state` (color/controller/metadata) when `server/activate` drops it from
  `active_roles`, *without* a preceding explicit-null `server/state` — different from the
  null-vs-absent `JsonOptional` fix already shipped in `spec-catchup-160-175.md` §1. Confirmed this
  codebase has **no `server/activate` message and no `active_roles`-diffing code at all**: there is
  no `applyServerActivate()` anywhere in `SendSpinClient.kt`; role activation is still read
  directly off `server/hello.active_roles` (a pre-encryption shape) with no re-activation message
  afterward. Implementing #275 would mean building the `server/activate`/`active_roles`-diff
  machinery this PR assumes already exists — which is squarely the parallel session's in-flight
  "activity-set table changes" work — rather than a self-contained fix on top of it. Building a
  throwaway version here risks exactly the merge conflict the task instructions asked to avoid.
  **Follow-up needed**: once the parallel session's `server/activate`/`active_roles` handling
  lands, whoever integrates it should add the discard-on-removal logic PR #275 describes (clear
  `_colorState`/`_controllerState`/metadata-derived state/artwork for every removed role that
  defines a `server/state` object) — it is not covered by anything in this pass.
- **[PR #274](https://github.com/Sendspin/spec/pull/274) "Clarify activation and shared stream
  lifecycle requirements"** — most of its normative changes (the initial-activation gate ending at
  `server/activate`, ignoring inactive-role objects, `server/unpair` sequencing) are about the
  `client/init`/`server/init`/`server/activate` handshake this codebase doesn't implement yet, for
  the same reason as #275. The lifecycle-ordering and `stream/clear`/`stream/end` MUST-clear
  clauses that *are* reachable today are already satisfied (see #270/#271 above).
- **[PR #265](https://github.com/Sendspin/spec/pull/265) "Report init failures with a cleartext
  `server/error`"** — `server/error` is sent in place of `server/init`, which this codebase never
  sends or receives (no `client/init`/`server/init` handshake). Not reachable until that layer
  exists.
- **A pre-existing, out-of-window bug found while reading `roles/player/v1.md` for PR #233/#288**:
  the spec's audio binary layout has carried a 4-byte `send_ahead` field between the timestamp and
  the audio payload (bytes 9-12) since
  [PR #167](https://github.com/Sendspin/spec/pull/167) "player role: carry the server's send-ahead
  on binary audio chunks" (merged 2026-08-28, inside the *previous* sync's #160-175 window and
  claimed done by `spec-catchup-160-175.md`). `MessageParser.kt`'s audio parsing was never actually
  updated for it — every audio chunk still passes bytes 9+ straight through as decodable payload,
  which means the 4 `send_ahead` bytes are silently corrupting the start of every audio chunk (and
  `min_buffer_ms` measurement is unimplemented, since it needs `send_ahead` to compute arrival
  delay). Left unfixed here since it's outside this PR window and non-trivial (touches the
  `AudioChunk` type and every audio-consuming test fixture) — flagged separately rather than folded
  into this batch.

## Verification

`./gradlew :sendspin-protocol:test --rerun-tasks` — 190 tests, 0 failures, 0 skipped (164 pre-
existing + 26 new: `ArtworkChunkingTest` ×9, `ClientLeaveTest` ×2, `PlayerFormatPreferenceTest` ×3,
new `SendSpinClientHelloTest` cases, `ClockSyncTest.toServerMicros`).
