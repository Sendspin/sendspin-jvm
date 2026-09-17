# Parse `send_ahead` in binary audio chunks

## Motivation

[Sendspin/spec PR #167](https://github.com/Sendspin/spec/pull/167) ("player role: carry the
server's send-ahead on binary audio chunks", merged 2026-08-28) added a 4-byte `send_ahead`
field (big-endian `uint32`, microseconds) to the binary audio chunk header, between the
timestamp and the encoded audio data. Per `roles/player/v1.md`, the header is now 13 bytes
(1 type + 8 timestamp + 4 send_ahead), and `send_ahead` is the input to the spec's
`min_buffer_ms` measurement: `arrival - compute_client_time(timestamp - send_ahead)`.

`MessageParser.parseBinary()` still treated everything from byte 9 onward as audio payload.
This wasn't just a spec-compliance gap — it was a real audio-corruption bug: the 4 `send_ahead`
bytes were silently prepended to every decoded `AudioChunk.data` as garbage samples/frame data.
It also meant `send_ahead` was never captured, so `min_buffer_ms` can't be computed.

Artwork (0x08-0x0B) and visualizer (0x10-0x15) binary frames were not affected — PR #167 scopes
the new field to audio (0x04) frames only; their header stays 9 bytes.

## Approach

- `MessageParser.parseBinary()`: for `BINARY_TYPE_AUDIO` specifically, reads bytes 9-12 as an
  unsigned big-endian `uint32` (`getInt().toLong() and 0xFFFFFFFFL`) into `sendAheadMicros`, and
  starts the audio payload at byte 13. Also added a dedicated length check (frame must be at
  least 13 bytes for audio) rather than the generic 9-byte minimum used by other binary types.
- `AudioChunk` (`Messages.kt`) gained a `sendAheadMicros: Long` field, defaulting to `0` so the
  existing `AudioChunk(timestamp, data)` call site in `AudioBufferTest` (which doesn't care about
  send-ahead) didn't need updating.
- `min_buffer_ms` measurement itself is not implemented in this pass — this only exposes the
  field so callers (e.g. a future `SendSpinClient` buffer-accounting change) can use it.
- Tests: `MessageParserTest`'s shared `buildBinaryFrame` helper (type + timestamp + payload, no
  send_ahead) is still correct for artwork/visualizer frames, which don't carry the field. Added
  a separate `buildAudioBinaryFrame` helper (type + timestamp + send_ahead + payload) and moved
  the two audio-specific tests to it. Added a regression test asserting `send_ahead` bytes don't
  leak into `AudioChunk.data`, and a short-frame test for an audio frame with a timestamp but no
  send_ahead bytes at all.

## Verification

`./gradlew :sendspin-protocol:test --rerun-tasks` — all tests pass.
