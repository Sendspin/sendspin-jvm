# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

Requires JDK 17. On macOS the system stub at `/usr/bin/java` may not work; set `JAVA_HOME` to a real JDK first (e.g. the Android Studio JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

```bash
./gradlew test                        # run all unit tests
./gradlew :sendspin-protocol:test     # run protocol tests only
./gradlew :conformance-client:jar     # build fat JAR for conformance harness
```

Gradle 9 auto-discovers `gradle/libs.versions.toml` — do **not** add a `versionCatalogs { from(...) }` block to `settings.gradle.kts`; that would cause a double-load error.

## Module layout

```
sendspin-protocol/      core JVM library (publishable artifact)
conformance-client/     CLI harness client (fat JAR, not published)
conformance-adapter/    Python wrapper that invokes the JAR from the harness
```

`conformance-client` depends on `:sendspin-protocol` and is a standalone program; it is never consumed as a library.

## Architecture

### State machine

`SendSpinClient` owns the protocol state machine:

```
IDLE → CONNECTING → HANDSHAKING → CLOCK_SYNCING → STREAMING → DISCONNECTED
                                                             ↘ ERROR
```

**CLIENT_INITIATED** (`connect(wsUrl)`): client dials the server; reconnects with exponential backoff on failure.

**SERVER_INITIATED** (`SendSpinServerHost` + `acceptIncomingConnection`): a `java-websocket` server listens on port 8928; incoming connections go through a `client/hello ↔ server/hello` handshake before `SendSpinClient` takes over the socket. `SendSpinServerHost` applies multi-server priority rules (playback > discovery, then last-played server by `server_id`).

### Clock sync (`ClockSync`)

NTP four-timestamp round-trip model fed into a 2D Kalman filter tracking offset (µs) and drift (µs/µs). `SendSpinClient` sends 8-probe bursts every 10 s; each `server/time` response calls `processMeasurement`. `AudioBuffer.offer()` calls `clockSync.toLocalMicros()` to schedule chunks. The first measurement flushes any pre-sync chunks via `AudioPlayer.flush()`.

### Audio buffer (`AudioBuffer`)

Priority queue ordered by local scheduled play time. Chunks more than 1 s late are dropped; chunks more than 30 s ahead are also dropped. On buffer full, the chunk scheduled furthest in the future is evicted (preserves near-term continuity). `staticDelayMicros` shifts all scheduled times for receiver-side delay compensation.

### Platform interfaces

`AudioPlayer`, `NsdBrowser`, `NsdRegistrar` are interfaces the host platform must implement. The conformance client uses `CollectingAudioPlayer` (collects raw encoded bytes) and `NoOp*` stubs for NSD.

### JSON serialization

Moshi with KSP-generated adapters (`@JsonClass(generateAdapter = true)`). `JsonOptional<T>` wraps fields that must distinguish JSON-absent from JSON-null — use `JsonOptional.Present` / `JsonOptional.Absent`.

### Conformance client (`conformance-client/Main.kt`)

CLI program invoked by the Python adapter. Accepts `--initiator-role server|client`, `--scenario-id`, `--preferred-codec flac|opus|pcm`, plus harness coordination flags (`--summary`, `--ready`, `--registry`, `--port`). Uses `SendSpinClient.onAudioChunk` hook to capture all raw encoded bytes before `AudioBuffer` drop logic applies. Writes a JSON summary and exits.

## Plans

Significant changes include a plan document in `plans/`. Each plan is a markdown file named after the feature (e.g., `plans/controller-repeat-shuffle.md`) and is committed alongside the code change. Plans describe the motivation, approach, and key decisions.

## Future simplifications

These workarounds exist because aiosendspin does not yet follow the spec in these areas. Remove them once the server catches up:

1. **`SEEK_HANDOFF_MS` in `SendSpinClient`**: The 500 ms window between `stream/end` and `audioPlayer.stop()` exists because aiosendspin sends `stream/end` + `stream/start` for natural track transitions. Spec-compliant behaviour would be gapless by timestamps with no stream boundary messages; once aiosendspin drops those messages for track transitions, this window (and the `pendingStopJob` logic) can be removed.

2. **`DROP_THRESHOLD_MICROS = 1 s` in `AudioBuffer`**: After a seek, Music Assistant pre-buffers audio with timestamps up to ~3 s in the past. Those chunks are dropped, causing a brief underrun; the audio player recovers when valid chunks arrive. Once Music Assistant adopts aiosendspin PR #237's `keep_stream=True` + `stream/clear` for seeks the stale burst no longer occurs and this can drop to ~500 ms.

3. **Safety-net `audioBuffer.flush()` in `SyncedAudioPlayer.transition()` (sendspin-android-tv)**: Music Assistant (via aiosendspin) does not yet reliably emit `stream/clear` before every `stream/start` on a song change. The spec says `stream/start` should not clear buffers; once `stream/clear` emission is reliable the flush becomes unconditional dead code.

## Publishing

`sendspin-protocol` is published via JitPack. Tag a release (`vX.Y.Z`) and JitPack builds automatically. Consumers add:

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// build.gradle.kts
implementation("com.github.OnFreund:sendspin-jvm:<version>")
```
