# Plan: Catch up on non-encryption spec changes (PRs #113, #115)

## Context

The Sendspin spec picked up a large batch of changes since this library was last synced
(main was at the 2026-06-09 volume/mute work). The biggest is a full Noise-protocol
encryption + PSK pairing rewrite of the connection handshake ([spec PR #84](https://github.com/Sendspin/spec/pull/84)
and follow-ups). That epic is out of scope here — encryption is optional for our clients at
this point: `aiosendspin`'s own [PR #298](https://github.com/Sendspin-Protocol/aiosendspin/pull/298)
confirms the server still defaults `allow_unencrypted=True` / `allow_noncompliant_clients=True`,
silently normalizing legacy plaintext connections. Seek support ([spec PR #103](https://github.com/Sendspin/spec/pull/103))
is already covered by the separate, still-open [PR #24](https://github.com/OnFreund/sendspin-jvm/pull/24).

Per [spec PR #113](https://github.com/Sendspin/spec/pull/113)'s own description, PR #113 plus
[PR #115](https://github.com/Sendspin/spec/pull/115) (the `state` → `available` rename) are
"the last client-breaking changes to the spec" outside of encryption. This plan covers what
those two require of a legacy (unencrypted) client, cross-checked against `aiosendspin`'s
`allow_noncompliant_clients` deviation catalog (`aiosendspin/server/connection.py`,
`compliance.py`, `models/player.py`) so the changes are verified against what the real
reference server actually checks today, not just the spec prose.

## Changes

### 1. `client/state`: `available: boolean` replaces the legacy `state` string

`ClientStateMsgPayload.state = "synchronized"` → `ClientStateMsgPayload.available = true`
([Messages.kt](../sendspin-protocol/src/main/kotlin/com/sendspin/protocol/Messages.kt)).
`aiosendspin` normalizes the old field (`available = state != "external_source"`) today, but
flags it as noncompliant and will eventually reject it once `allow_noncompliant_clients`
defaults to `False`.

Kept as an unconditional `true` (not gated on clock-sync completion) rather than following the
spec's "don't report available until synced" literally: `aiosendspin`'s `available: false`
handling calls `_handle_external_source_transition()`, which evicts the client from its shared
group into a stopped solo group. Sending `false` on every initial connect would kick the player
out of its group on every reconnect — worse than the deviation it's meant to fix. `available`
defaults to `true` server-side anyway when the field is absent, so this is a pure field rename
with no behavior change.

### 2. `client/state` player object: declare `set_static_delay` in `supported_commands`

Added `PlayerStatePayload.supportedCommands = listOf("set_static_delay")`. This is a real bug
fix, not just spec hygiene: `aiosendspin`'s `player/v1.py` (`set_static_delay()`) silently
no-ops unless the client has declared `set_static_delay` in this field
(`state_supported_commands`). The client already handles the resulting `server/command`
(`SendSpinClient.handleTextMessage`, `"set_static_delay"` branch, added in the
[persist-static-delay](persist-static-delay.md) work) — but the server was never sending it
because we never declared support. Server-driven delay pushes (e.g. from a Music Assistant UI)
were silently broken.

### 3. `group/update`: drop `volume`/`muted`

The spec's `group/update` payload is now `playback_state` / `group_id` / `group_name` only —
confirmed against `aiosendspin`'s `GroupUpdateServerPayload`, which has no volume/muted fields.
Removed the corresponding fields from `GroupUpdate` and the merge-into-`ControllerState` logic
in `SendSpinClient`, which was already dead in practice (a real server never populates them; the
group's volume/muted live in the controller role's `server/state` object exclusively).

### 4. Persist `volume`/`muted` across restarts

Spec PR #113: "persisting volume/muted across reboots is RECOMMENDED". Added
`ClientSettingsKeys.PLAYER_VOLUME` / `PLAYER_MUTED`, following the same
`ClientSettingsStore` pattern as `static_delay_ms` ([persist-static-delay.md](persist-static-delay.md)).

### 5. Regression test for forward compatibility

Spec PR #113 makes "clients MUST ignore unrecognized payload fields" an explicit requirement.
Moshi's generated adapters already do this by default (unmatched JSON names are skipped), so no
code change was needed — added a test in `MessageParserTest` asserting an unknown field in a
`server/hello` payload doesn't break parsing, to lock the behavior in.

## Explicitly out of scope

- **Encryption / Noise handshake / pairing (PSK, PIN flows, CPace PAKE, `management` role,
  records)** — the current legacy unencrypted `client/hello` ↔ `server/hello` flow matches
  `aiosendspin`'s `LegacyServerHelloPayload` exactly and continues to work as long as the server
  runs in transition mode. This is a separate, much larger effort with its own plan when needed.
- **`source@v1` role** — new capture/input role, not relevant to a playback-only client.
- **`stream/request-format`** — pre-existing gap (predates this batch of spec changes), not
  implemented by this client at all yet. Left for a future, separately-scoped change.
- **`stream/clear` `roles` field** — the spec allows `stream/clear` to target only `player` or
  only `visualizer`; this client still unconditionally flushes on any `stream/clear`. Pre-existing
  gap, not part of this batch — flagged separately, not fixed here.
- **`group_id`/`group_name` on `group/update`** — no current consumer/UI feature needs them.
