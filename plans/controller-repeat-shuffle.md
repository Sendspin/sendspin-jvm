# Controller repeat/shuffle (spec PR #81)

## Context

The SendSpin spec moved `repeat` and `shuffle` from the metadata role into the controller role. New servers send these fields inside the `server/state → controller` object. Old servers still send them in `metadata`.

## Approach

- Added `repeat: String?` and `shuffle: Boolean?` to `ControllerState` (new canonical location).
- Kept `repeat`/`shuffle` in `TrackMetadataMsg` for parsing from old servers, but marked `@Deprecated`.
- `SendSpinClient.mergeControllerWithMetadata()` resolves the effective value on every `server/state` message:
  - If `controller` is present: use its values; fall back to metadata for any null field.
  - If only metadata has repeat/shuffle (old server, no controller object): synthesize a controller state update from the current `_controllerState` value.
  - Controller values always win over metadata values when both are present.
- `ClientHelloPayload.version` is unchanged (the wire protocol version did not change).
- Library release tagged **v2.0.0** (major bump — canonical field location changed).

## Future

Remove the legacy metadata fallback once old servers are no longer in use (tracked in GitHub issue).
