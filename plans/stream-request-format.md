# Emit `stream/request-format`

## Motivation

Per the [Sendspin spec](https://github.com/Sendspin/spec) "Client → Server: stream/request-format"
section, a player-role client can ask the server to switch the active stream to a different
codec/channels/sample-rate/bit-depth combination (e.g. to adapt to changing network or CPU
conditions), and the server responds with a fresh `stream/start` if a stream is active. The
message struct and outgoing-message wiring for this didn't exist in the SDK yet (issue #16),
so there was no way for a host app to trigger renegotiation.

## Approach

Follows the existing outgoing-message shape used by `ClientCommand` et al. (`type` + `payload`):

- `Messages.kt` gains `PlayerFormatRequest` (all-optional `codec`/`channels`/`sample_rate`/
  `bit_depth`, mirroring `AudioFormat`'s field names), `StreamRequestFormatPayload` (`player?`),
  and `StreamRequestFormat` (`type = "stream/request-format"`, `payload`).
- `SendSpinClient.requestPlayerFormat(codec?, channels?, sampleRate?, bitDepth?)` builds and sends
  the message on the active socket, following the same null-socket guard and logging pattern as
  `sendControllerCommand`/`sendSeek`.
- Only the `player` object is implemented — `artwork`/`visualizer` renegotiation objects exist in
  the spec but aren't needed by the issue's network/CPU-adaptation use case; they can be added to
  `StreamRequestFormatPayload` later if a use case arises.
- Requesting a combination is the caller's responsibility to keep within
  `ClientPreferences.supportedFormats`, matching how other outgoing commands don't self-validate.

## Follow-up

Per a maintainer note on the issue, this alone won't turn the conformance harness's
`client-initiated-request-format-pcm`/`-flac` scenarios green: `Sendspin/conformance`'s
`implementations.py` statically declares `supports_request_format=False` for `sendspin-jvm` and
gates those scenarios on that flag rather than runtime probing. A follow-up PR in
`Sendspin/conformance` needs to flip that flag once this ships.
