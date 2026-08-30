# Plan: Catch up on non-encryption spec changes (PRs #160-#175)

## Context

Late-August 2026 batch of [Sendspin/spec](https://github.com/Sendspin/spec) PRs. A parallel
effort (see `plans/encryption-support.md` on the `claude/encryption-support-plan-61ed85` branch)
covers the encryption/pairing/management portions of the same round of spec activity (Sentinel
Fallback, `psk_category`, `trust_level` removal, escalation threshold). That branch predates this
one's `client/hello` configurability work (PR #31) and hasn't merged yet, so this repo currently
has no Noise/encryption layer at all — every connection is still the legacy cleartext
`client/hello` ↔ `server/hello` handshake. This plan covers everything from the batch that isn't
encryption-shaped, checked against the current (pre-encryption) codebase.

## Changes

### 1. [PR #175](https://github.com/Sendspin/spec/pull/175) "Send full state instead of deltas"

No code change needed — `SendSpinClient.kt`'s `is ServerState ->` and `is GroupUpdate ->`
branches, and `sendClientState()`/`buildClientHello()`, already behave the way the new wording
requires:

- `sendClientState()` always builds a complete `PlayerStatePayload` from current in-memory state
  (volume, muted, delay, lead time, buffer) on every call — never a partial/changed-fields-only
  payload. Matches "every message MUST carry `available` and the full state of each role object
  it includes."
- `is GroupUpdate ->` only ever read `playback_state` (the merge-in of `volume`/`muted` was
  already removed per PR #113/#115, see `spec-catchup-113-115.md`); the new full-state wording
  doesn't change what this client needs to do with it.

**Real bug found and fixed:** the spec's "a role object set to `null` clears all of that role's
state" (unchanged by #175 — it was already true under the old delta model) wasn't actually
honored. `ServerState.metadata`/`controller`/`color` were plain nullable Kotlin fields, so Moshi
couldn't distinguish "key absent" (leave unchanged) from "key present with JSON `null`" (clear) —
both deserialized to Kotlin `null`, and the handler treated both as "unchanged" (e.g.
`if (msg.color != null) _colorState.value = msg.color` never ran on an explicit-null message, so
`_colorState` just kept whatever it last held).

Originally left as a documented-but-unfixed gap in this pass, on the reasoning that it predates
this spec batch and that this client doesn't implement `server/activate` (the one place the spec
documents a server actually sending an explicit-null role object), so the scenario might not be
reachable yet. Checked the parallel encryption branch (`claude/encryption-support-plan-61ed85`,
session `magical-cray-6386cd-ee`), which *does* implement `server/activate` — its handler had the
identical bug, and its `applyServerActivate()` doesn't compensate either (it only updates
`_activeRoles`, relying on the server's null `server/state`/`stream/end` to actually clear
role state, which then hit this bug). Flagged it to that session; they confirmed it's non-crypto
(no Noise/PSK/pairing involvement) and asked this session to own it instead, since it's part of
`server/state` handling rather than their transport-framing work.

First fix attempt wrapped `ServerState.metadata`/`controller`/`color` in `JsonOptional<T>` (the
pattern already used for `TrackMetadataMsg`'s leaf fields). That broke CI's `conformance` check:
it builds `Sendspin/conformance`'s own `sendspin-jvm` adapter (`adapters/sendspin-jvm/client/`,
a file in the *conformance* repo, not this one) against this branch as a library dependency, and
that adapter reads `serverState.metadata` as a plain `TrackMetadataMsg?` — a real external
consumer this repo can't fix by pushing here. Reworked to keep the public field types unchanged:

- `Messages.kt`: `ServerState.metadata`/`controller`/`color` stay plain nullable (`TrackMetadataMsg?`
  etc., unchanged from before this PR) — source-compatible with every existing consumer. Added a
  new field, `explicitlyNulledRoles: Set<String> = emptySet()`, naming which of
  `"metadata"`/`"controller"`/`"color"` arrived as an explicit JSON `null` on this message. Being
  additive with a default, it doesn't break positional construction or `copy()` calls either.
- `JsonOptional.kt`: added `ServerStateJsonAdapter`, a hand-written (not KSP-generated) Moshi
  adapter that's the only way to actually populate `explicitlyNulledRoles` — a `@JsonClass`-codegen
  or reflection-based adapter can't distinguish "key absent" from "key present as `null`" for a
  plain nullable field, which is exactly the ambiguity being resolved. Wired into
  `JsonOptionalAdapterFactory.create()` (intercepting `ServerState::class.java` specifically)
  rather than needing a separate Moshi registration — every consumer already registers that
  factory to use `JsonOptional`-typed fields elsewhere in this library, so this required no
  consumer-side Moshi setup change.
- `SendSpinClient.kt`: the `is ServerState ->` handler checks `msg.color`/`explicitlyNulledRoles`
  directly (non-null → update, `in explicitlyNulledRoles` → clear, neither → leave `StateFlow`
  untouched). `controller` needed more care because of the deprecated repeat/shuffle-via-metadata
  merge (`mergeControllerWithMetadata`) — that function returns a small `ControllerUpdate` sealed
  result (`NoChange`/`Clear`/`Set`) instead of a bare `ControllerState?`, since a plain nullable
  return type couldn't distinguish "nothing to do" from "clear it."
- Tests: parse-level coverage (`MessageParserTest`: role objects omitted → absent from
  `explicitlyNulledRoles`, explicit `null` → present in it) and behavioral coverage
  (`ControllerMergeTest`: explicit-null clears controller state, omitted key leaves it unchanged;
  `SendSpinClientHelloTest`: same two cases for `color`). All prior merge-precedence behavior
  (controller wins over legacy metadata repeat/shuffle, etc.) is unchanged and still covered by
  the pre-existing tests, all of which still pass.

### 2. [PR #172](https://github.com/Sendspin/spec/pull/172) "Give fragmentation a single ID"

Fragmentation was not implemented before this change (confirmed: no `fragment` references
anywhere in `sendspin-protocol/src/main`) and still isn't, by design decision, not oversight.

The fragmentation mechanism exists specifically to work around the Noise transport's 65535-byte
AEAD message limit (65518 bytes of application payload after the type byte and auth tag) — see
`connection.md`/`messaging.md`. This codebase has no Noise/encryption layer yet (see Context
above), so WS binary frames aren't wrapped in Noise ciphertext and that specific cap doesn't apply
here. Implementing the fragment wire format now would be dead code with no real constraint to test
it against.

Checked current payload sizes against the cap anyway, in case a non-Noise reason to fragment
exists:
- Audio chunks: spec caps chunk duration at 150 ms. Worst case (PCM, 48 kHz, 2ch, 24-bit):
  0.15 s × 48000 × 2 × 3 = 43,200 bytes + 9-byte header — comfortably under 65518.
- Artwork: default channel config is 800×800 JPEG/PNG; even the letterboxed max declared in tests
  (1920×1080) stays well under 65 KB for compressed JPEG/PNG at reasonable quality.

Decision: not implementing fragmentation in this pass — confirmed with the parallel
encryption-branch session (`claude/encryption-support-plan-61ed85`), which is pulling PR #172
into its own scope: fragmentation sits directly under Noise transport decryption (same layer as
the type=0 JSON / type=4-7 role dispatch that branch already owns), not a role-specific concern,
so it belongs with that work rather than here. That session will implement it directly in the new
single-ID format (`[1][flags][orig_type][data]` / `[1][flags][data]`, flags bit 1 = first, bit 0 =
last) and update `plans/encryption-support.md` accordingly. No TODO left in this codebase's code
since there's nothing to hang one on yet (no binary-frame-sending path here is
Noise-message-size-constrained).

### 3. [PR #164](https://github.com/Sendspin/spec/pull/164) "rename static_delay_ms to output_delay_ms"

Real breaking wire rename, implemented throughout:

- `Messages.kt`: `PlayerStatePayload.staticDelayMs` → `outputDelayMs` (wire `static_delay_ms` →
  `output_delay_ms`), `ServerCommandPlayerPayload.staticDelayMs` → `outputDelayMs`, default
  `supportedCommands` entry `"set_static_delay"` → `"set_output_delay"`.
- `SendSpinClient.kt`: `staticDelayMs` field, `setStaticDelayMs()` → `setOutputDelayMs()`, the
  `"set_static_delay"` command-name branch → `"set_output_delay"`, log format strings.
- `ClientSettingsStore.kt`: `ClientSettingsKeys.STATIC_DELAY_MS` → `OUTPUT_DELAY_MS` (persisted
  key also renamed — existing installs lose their calibrated delay on upgrade, which is expected
  for this pre-1.0 library, same as prior renames).
- `AudioBuffer.kt`: public `staticDelayMicros` field → `outputDelayMicros` (this is public API —
  `SendSpinClient.audioBuffer` is a public property — so this is a breaking rename for host apps
  too, consistent with the wire rename it mirrors).
- Tests: `StaticDelayPersistenceTest.kt` renamed to `OutputDelayPersistenceTest.kt` and updated;
  `AudioBufferTest.kt`, `VolumeCurveTest.kt`, `MessageParserTest.kt` updated to the new names.

### 4. [PR #163](https://github.com/Sendspin/spec/pull/163) "rename client_stream/* to client-stream/*"

No-op, confirmed: `source@v1` is not implemented anywhere in this codebase (no `client_stream`,
`client-stream`, `source@v1`, or `SourceSupport` references in `sendspin-protocol/src` or
`conformance-client/src`).

### 5. [PR #168](https://github.com/Sendspin/spec/pull/168) "Letterbox art and remove BMP support"

- BMP: this client never declared `'bmp'` as a supported artwork format (`ArtworkChannel.format`
  defaulted to `"jpeg"`), so nothing to remove.
- `media_width`/`media_height` → `width`/`height`: renamed in `ArtworkChannel` (the
  `artwork@v1_support` object sent in `client/hello`). `StreamArtworkChannel` (the server's
  `stream/start` artwork config) already used `width`/`height` — no change needed there.
- Letterboxing itself (server pads to the declared exact dimensions with black bars instead of
  the old "scale to fit, client gets whatever aspect ratio results" behavior) is purely a
  server-side rendering change. This client has never made any assumption about the aspect ratio
  of the raw image bytes it receives — it just forwards them via `_albumArtwork`/`_artistArtwork`
  to the host app — so there's no client-side behavior to update beyond the field rename.

### 6. [PR #160](https://github.com/Sendspin/spec/pull/160) "make binary message ID table normative"

Editorial (descriptive bit-layout prose → a normative ID table; the actual ID assignments are
unchanged: player 4-7, artwork 8-11, source 12-15, visualizer 16-23). Checked
`MessageParser.kt`'s binary dispatch (`BINARY_TYPE_AUDIO = 0x04`, `BINARY_TYPE_ARTWORK_0..3 =
0x08..0x0B`, `BINARY_TYPE_VISUALIZER_* = 0x10..0x15`) — already matches. No code change.

### 7. PRs [#161](https://github.com/Sendspin/spec/pull/161), [#162](https://github.com/Sendspin/spec/pull/162), [#165](https://github.com/Sendspin/spec/pull/165), [#166](https://github.com/Sendspin/spec/pull/166)

Read each diff in full. All editorial-only:

- **#161**: promotes several `**Note:**` blocks containing actual requirements to normative body
  text (e.g. "server MUST first end that role's output..."), drops redundant `**Note:**` framing
  elsewhere. No new requirement beyond what was already documented (just moved out of a
  non-normative block); nothing this client does differently.
- **#162**: drops the "Sendspin " prefix from defined terms per the new `CONTRIBUTING.md`
  "one canonical name per term" rule (`Sendspin Server` → `Server`, `Sendspin Group` → `Group`,
  `Sendspin Trust Level` → `Trust Level`, etc.) and un-prefixes several mentions in body text.
  Pure renaming of prose terms, no wire/behavior change.
- **#165**: same `**Note:**`-promotion pattern as #161 (volume/mute independence, artwork format
  support becoming MUST, etc.) plus removes a stray double-blank-line. No behavior change.
- **#166**: adds a new `## Editorial rules` section to `CONTRIBUTING.md` documenting the rules
  the spec's own source files already follow (self-contained role files, `client/hello` vs
  `client/state` field placement, canonical naming, non-normative `Note:` blocks). Contributor
  documentation only, no spec content change.

No code changes for any of these four.

## Verification

`./gradlew :sendspin-protocol:test` — 164 tests, 0 failures, 0 skipped.

## Explicitly out of scope

- **Encryption/pairing/management** — covered by the parallel `encryption-support.md` effort,
  not yet merged into this branch.
- **PR #172 fragmentation** — owned by the parallel encryption-branch session (see #2 above);
  it belongs with the Noise transport-framing work, not here.
- **Applying the `ServerState` null-vs-absent fix (see #1) to the encryption branch** — that
  branch's `SendSpinClient.kt`/`Messages.kt` have their own copy of the same code; this pass only
  fixed it here. Whoever reconciles the two branches needs to port it (or re-derive it — it's a
  small, self-contained change) rather than assume a merge picks it up automatically.
