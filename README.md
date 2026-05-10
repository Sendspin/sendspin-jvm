# sendspin-jvm

JVM client library for the [Sendspin](https://github.com/Sendspin) audio streaming protocol. Pure Kotlin/JVM — no Android dependencies.

## Features

- **Dual connection modes** — CLIENT_INITIATED (connect to server) and SERVER_INITIATED (accept incoming server connection)
- **NTP-style Kalman clock sync** — continuous offset and drift estimation for sample-accurate playback scheduling
- **Audio buffering** — timestamp-ordered buffer with drop/late-chunk tracking
- **Moshi JSON** — KSP-generated adapters for protocol messages; zero reflection at runtime
- **Coroutines API** — `StateFlow`/`SharedFlow` for state and diagnostics; suspend-friendly throughout

## Getting started

Add JitPack to your repositories and declare the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.OnFreund:sendspin-jvm:<version>")
}
```

Replace `<version>` with a tag from the [releases page](https://github.com/OnFreund/sendspin-jvm/releases), e.g. `v0.1.0`.

## Key classes

| Class / Interface | Role |
|---|---|
| `SendSpinClient` | Core WebSocket client; owns the state machine and clock sync loop |
| `ClockSync` | Kalman-filter clock estimator — tracks offset (ms) and drift (ppm) |
| `AudioBuffer` | Priority-queue buffer that orders chunks by server timestamp |
| `AudioPlayer` | Interface — implement to feed decoded frames to your audio sink |
| `NsdBrowser` | Interface — implement for mDNS/DNS-SD server discovery |
| `NsdRegistrar` | Interface — implement to advertise your client on the local network |
| `SendSpinServerHost` | Accepts incoming WebSocket connections from servers |

## Protocol overview

### State machine

```
IDLE → CONNECTING → HANDSHAKING → CLOCK_SYNCING → STREAMING → DISCONNECTED
                                                             ↘ ERROR
```

CLIENT_INITIATED mode reconnects automatically with exponential backoff. SERVER_INITIATED mode waits for the server to reconnect — no client-side backoff.

### Binary frame layout

```
[0]      1 byte  – message type (0x04 = audio, 0x08–0x0B = artwork channels 0–3)
[1..8]   8 bytes – big-endian int64 server timestamp in microseconds
[9..]    N bytes – codec-encoded audio data
```

Text frames carry JSON-encoded control messages (server/hello, server/state, server/time, stream/start, stream/end, group/update).

## Building and running tests

Requires JDK 17+.

```bash
./gradlew test
```

Test results land in `**/build/reports/tests/`.

## Conformance testing

This library is validated against the [Sendspin conformance harness](https://github.com/Sendspin/conformance).

Build the conformance JAR:

```bash
./gradlew :conformance-client:jar
```

Then follow the harness README to run:

```bash
conformance run --from aiosendspin --to sendspin-jvm --timeout-seconds 30
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
