# Parse `stream/clear` roles field

## Motivation

Per the [Sendspin spec](https://github.com/Sendspin/spec) "Server → Client: stream/clear"
section, `stream/clear` carries an optional `roles?: string[]` field naming which roles to
clear (`player`, `visualizer`, or both — omitted means both). `StreamClear` was a zero-field
`object`, so `MessageParser` discarded the payload entirely and `SendSpinClient` unconditionally
flushed the audio player on every `stream/clear`, including ones scoped to `visualizer` only.

This was scoped out of a prior spec-catchup change as a pre-existing gap unrelated to that
batch of spec changes.

## Approach

Mirrors the existing `StreamEnd` role-filtering shape already used in the same files:

- `StreamClear` becomes a data class with `roles: List<String>? = null` instead of a bare
  `object` (`Messages.kt`).
- `MessageParser` parses the `stream/clear` payload through a Moshi adapter instead of
  returning the bare object.
- `SendSpinClient`'s `is StreamClear ->` branch only calls `audioPlayer.flush()` when
  `roles` is `null` or contains `"player"`/`"player@v1"`, matching the role-check pattern used
  for `StreamEnd`.
