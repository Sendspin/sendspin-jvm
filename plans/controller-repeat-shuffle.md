# Plan: Move repeat/shuffle to controller role

## Context

The SendSpin spec ([PR #81](https://github.com/Sendspin/spec/pull/81)) moves `repeat` and `shuffle` from the metadata role into the controller role. New servers will send these fields inside the `server/state` → `controller` object instead of `metadata`. We must:
- Support new servers by parsing repeat/shuffle from `ControllerState`
- Stay backward-compatible with old servers that still send them in `TrackMetadataMsg`
- When both are present, prefer the controller values
- Bump the library version — this PR is a **major** bump (v2.0.0). Adding constructor parameters to a Kotlin `data class` changes the JVM binary signature of `copy`, `copy$default`, and `componentN`, which is binary-incompatible even though the source API is backward compatible. The deprecated-but-not-removed metadata fields are a separate concern tracked in issue #3.

## Changes

### 1. `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/Messages.kt`

**`ControllerState`** — add two new nullable fields (nullable, not JsonOptional, consistent with the existing volume/muted pattern):
```kotlin
data class ControllerState(
    @Json(name = "supported_commands") val supportedCommands: List<String> = emptyList(),
    @Json(name = "volume") val volume: Int = 100,
    @Json(name = "muted") val muted: Boolean = false,
    @Json(name = "repeat") val repeat: String? = null,       // ← add
    @Json(name = "shuffle") val shuffle: Boolean? = null,    // ← add
)
```

**`TrackMetadataMsg`** — keep repeat/shuffle (they're still parsed from old servers), but mark `@Deprecated` to signal the canonical location has moved:
```kotlin
@Deprecated("Sent by old servers only; use ControllerState.repeat instead")
@Json(name = "repeat") val repeat: JsonOptional<String> = JsonOptional.Absent,
@Deprecated("Sent by old servers only; use ControllerState.shuffle instead")
@Json(name = "shuffle") val shuffle: JsonOptional<Boolean> = JsonOptional.Absent,
```

### 2. `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt`

Replace the current `ServerState` handling block with merge logic:

```kotlin
is ServerState -> {
    // ... existing title/progress logging unchanged ...
    val effectiveController = mergeControllerWithMetadata(msg)
    if (effectiveController != null) {
        _controllerState.value = effectiveController
    }
    _serverState.tryEmit(msg)
    if (_state.value == ClientState.CLOCK_SYNCING || _state.value == ClientState.STREAMING) {
        _state.value = ClientState.STREAMING
    }
}
```

Add private helper (after the `handleTextMessage` function):
```kotlin
private fun mergeControllerWithMetadata(msg: ServerState): ControllerState? {
    val ctrl = msg.controller
    return when {
        ctrl != null -> {
            // New server path: controller is fully authoritative.
            // Do NOT fall back to metadata — ctrl.repeat == null means the server cleared
            // the field, and we must not re-populate it from the legacy metadata source.
            ctrl
        }
        else -> {
            // Old server path: no controller object; apply metadata repeat/shuffle to the
            // existing controller state. Only update if the field is Present (including
            // Present(null) = explicit clear). Skip if no prior controller state exists to
            // avoid inventing volume/muted defaults.
            val rawRepeat  = msg.metadata?.repeat  ?: JsonOptional.Absent
            val rawShuffle = msg.metadata?.shuffle ?: JsonOptional.Absent
            if (rawRepeat !is JsonOptional.Present && rawShuffle !is JsonOptional.Present) return null
            val current = _controllerState.value ?: return null
            current.copy(
                repeat  = if (rawRepeat  is JsonOptional.Present) rawRepeat.value  else current.repeat,
                shuffle = if (rawShuffle is JsonOptional.Present) rawShuffle.value else current.shuffle,
            )
        }
    }
}
```

### 3. Tests — `MessageParserTest.kt` and `ControllerMergeTest.kt`

`MessageParserTest.kt` — add three cases:
- **New server**: `server/state` with `controller: { "repeat": "all", "shuffle": true }` → parsed into `ControllerState`
- **Old server**: `server/state` with only metadata `repeat`/`shuffle` → legacy fields still parse correctly
- **Controller without repeat/shuffle**: defaults to null

`ControllerMergeTest.kt` (new file) — verify the `_controllerState` StateFlow via `handleTextMessage`:
- New server: controller repeat/shuffle used directly
- Old server with prior controller state: metadata repeat/shuffle update the existing state
- Old server without prior controller state: metadata repeat/shuffle ignored (no state to update)
- Both present: controller wins (including null controller values — no metadata fallback)
- Old server: `Present(null)` in metadata explicitly clears repeat or shuffle
- Neither present: controller state unchanged

### 4. Plans directory and CLAUDE.md

Create `plans/` at the repo root and commit this plan alongside the code change. Update `CLAUDE.md` to document the convention: plans for significant changes go in `plans/` as markdown files committed with the change.

### 5. GitHub issue — remove backward compat

Open an issue to track eventual removal of the legacy metadata fallback and the `@Deprecated` fields from `TrackMetadataMsg`. That removal will be another major version bump.

### 6. Version tagging

Tag this release as **v2.0.0**. Adding constructor parameters to a `data class` changes the JVM binary ABI (`copy`, `copy$default`, `componentN` signatures), which is binary-incompatible regardless of source compatibility. The follow-up removal of deprecated metadata fields will be a further major bump.

## Critical files
- `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/Messages.kt` — data classes
- `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt` — merge logic (around line 326-339)
- `sendspin-protocol/src/test/kotlin/com/sendspin/protocol/MessageParserTest.kt` — existing parser tests (extend)
- `sendspin-protocol/src/test/kotlin/com/sendspin/protocol/ControllerMergeTest.kt` — new merge tests

## Verification
```bash
./gradlew :sendspin-protocol:test
```
All existing tests must still pass. New tests cover all merge scenarios (controller-only, metadata-only/old server, both present, neither present).

The conformance client (Sendspin/conformance repo) captures audio chunks and does not currently report controller state, so **no changes to the conformance client are expected**. Run the conformance harness against the updated fat JAR to confirm no regressions.
