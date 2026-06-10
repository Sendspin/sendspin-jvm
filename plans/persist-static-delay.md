# Persist static_delay_ms across restarts

## Motivation

[Sendspin/sendspin-jvm#9](https://github.com/Sendspin/sendspin-jvm/issues/9), filed from a cross-SDK
conformance audit, claimed two gaps: `static_delay_ms` wasn't applied to playback scheduling, and it
wasn't persisted across restarts.

The first claim does not hold up: `SendSpinClient.setStaticDelayMs()` (called from the
`set_static_delay` server command handler) sets `audioBuffer.staticDelayMicros`, and
`AudioBuffer.scheduledMicros()` subtracts it when computing each chunk's playback time. So
`static_delay_ms` was already applied correctly — no change needed there.

The second claim was real: `staticDelayMs` lived only in a volatile in-memory field on
`SendSpinClient`, so the value reset to 0 on every restart instead of surviving as the spec
requires.

## Approach

`sendspin-protocol` is a platform-agnostic JVM library with no filesystem/Android assumptions,
so persistence is delegated to the host app via a new `ClientSettingsStore` interface
(`ClientSettingsStore.kt`), following the existing pattern used for `AudioPlayer`,
`NsdBrowser`, and `NsdRegistrar`.

The store is a generic key/value interface (int and string accessors) rather than being
specific to static delay, since other settings (e.g. last-played server id) will likely need
the same treatment in the future.

`SendSpinClient`:
- Takes an optional `settingsStore: ClientSettingsStore` constructor parameter, defaulting to
  `NoOpClientSettingsStore` so existing call sites are unaffected.
- Loads `static_delay_ms` from the store on construction and applies it to
  `audioBuffer.staticDelayMicros` immediately.
- Persists the value via `settingsStore.putInt(...)` whenever `setStaticDelayMs()` is called
  (including from the `set_static_delay` server command handler).

Host apps implement `ClientSettingsStore` on top of their platform's preferred storage
(e.g. preferences APIs, a file) to wire up actual persistence.
