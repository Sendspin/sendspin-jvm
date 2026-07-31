# Plan: Catch up on spec PR #122 (pre-1.0 clarifications)

## Context

[Spec PR #122](https://github.com/Sendspin/spec/pull/122) is a large batch of pre-1.0
clarifications across `README.md` and the role docs. `connection.md` / `messaging.md` /
`pairing.md` / `management.md` (encryption/pairing) were already reviewed in a separate pass.
This plan covers the remaining files: `roles/artwork/v1.md`, `roles/controller/v1.md`,
`roles/metadata/v1.md`, `roles/player/v1.md`, `roles/visualizer/v1.md`, and the README's group
volume/mute formula fix.

`roles/source/v1.md` is out of scope: `sendspin-jvm` doesn't implement the `source` role at all
(confirmed by `grep -rn "source@v1"` turning up nothing outside this plan).

## Review outcome

Most of PR #122 is prose clarification of behavior this client already has right, or server-side
behavior with no client-observable effect. One real bug was found and fixed.

### Fixed

**`client/state` sent a `player` object even when `player` wasn't in the server's active roles.**
`sendClientState()` ([SendSpinClient.kt](../sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt))
unconditionally built and sent a `PlayerStatePayload`. Per `messaging.md`'s `client/state`
section ("`player?`: object - only if client has `player` role"), this is only valid when
`player` is active. Not new in PR #122, but adjacent to its "required role support objects"
theme, and confirmed live by a conformance-harness log line: "non-compliant client: client/state
carried a player object for an inactive role".

This client uses the legacy unencrypted `client/hello` ↔ `server/hello` handshake (see
[spec-catchup-113-115.md](spec-catchup-113-115.md)), not the newer `server/activate` message, so
`ServerHello.activeRoles` from the initial hello is the only source of truth for which roles are
active — there's no dynamic re-activation to track. `sendClientState()` now omits `player` unless
`"player@v1"` or `"player"` is present in `activeRoles`. Covered by
`ClientStatePlayerRoleTest.kt`.

### Confirmed already compliant, no change

- **Artwork lateness** (`roles/artwork/v1.md`): "Artwork is never dropped for lateness" — the
  client's `BinaryArtwork` handling (`SendSpinClient.handleBinaryMessage`) never inspects the
  timestamp for dropping; it just applies the latest image per channel. Matches by construction.
- **Artwork `channels` array covers every declared channel index** (with `source: 'none'` for
  unstreamed ones): the client already indexes into `preferences.artworkChannels` /
  `stream/start`'s `artwork.channels` by position, so a wider, order-preserving array needs no
  code change.
- **Metadata clock-domain clarification** (`roles/metadata/v1.md`): `ClockSync.calculateProgress`
  already takes `baseLocalTimeMicros` as an explicitly *local*-domain parameter (its KDoc says
  so), and `SendSpinClient.toLocalMicros()` is public for converting `metadata.timestamp` before
  calling it. There's no in-library caller doing this conversion incorrectly (there's no
  in-library caller at all — progress calculation is left to the host app), so nothing to fix
  here.
- **Visualizer `uint16` fields are big-endian**: `MessageParser`'s visualizer parsers use
  `ByteBuffer.wrap(...)` without changing `order()`, and `ByteBuffer`'s default order is
  big-endian. Already correct.
- **Group volume/mute formula fix** (`README.md`): this is a server-side computation.
  `sendspin-jvm` is a pure receiver of `group/update` (confirmed: `group/update` has carried no
  volume/muted fields since [spec-catchup-113-115.md](spec-catchup-113-115.md) moved that into
  the controller role's `server/state`). N/A for a client.

### Documentation-only, no client code affected

- Player `codec` field narrowed to `'opus' | 'flac' | 'pcm'`, `bit_depth` "ignored for opus":
  these fields are plain `String`/`Int` pass-through in `Messages.kt`; the client doesn't
  interpret or validate them, so narrowing the spec's allowed values changes nothing here.
- Player/source codec framing rules (one codec unit per chunk, `codec_header` is standard
  Base64): this library passes `codec_header` and chunk payloads through raw to the
  `AudioPlayer` implementation; encoding/decoding is entirely the host app's responsibility.
- `stream/start` in-place vs. fresh-stream lead-time scheduling: purely a server-side scheduling
  nuance; the client just plays back whatever timestamps arrive.
- Controller "group volume changes don't affect `muted`" note: informational cross-reference,
  no behavior implied for a client that doesn't compute group state.

## Out of scope

- `roles/source/v1.md`: role not implemented by this client.
