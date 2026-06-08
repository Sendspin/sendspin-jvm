# Issue #10: in-SDK clock-drift correction (sample insert/drop)

## Context

Issue #10 asks whether sendspin-jvm should perform in-SDK drift correction via sample
insertion/dropping during playback, matching peer SDKs (sendspin-rs, sendspin-cpp,
sendspin-dotnet, SendspinKit, sendspin-cli). Today sendspin-jvm only provides a Kalman-filtered
offset/drift estimate (`ClockSync`) and leaves queue scheduling/playback to the embedder
(`AudioBuffer` + the consumer's player, e.g. android-tv's `SyncedAudioPlayer`), which polls the
buffer and writes PCM in blocking mode with no active correction.

A past attempt to align consumption with the reference "burst-then-best" strategy caused audible
pops and underruns and was reverted. Root cause: `AudioBuffer.offer()` bakes `localPlayTimeMicros` into the queue once at arrival
time using the `ClockSync` estimate at that moment, and never revisits already-queued chunks.
Per-sample Kalman updates (current behavior, ~every 200ms during a burst) move the offset in
small steps, so no seam is audible. Batching to one larger correction per ~10s burst produces a
big discrete jump — chunks already queued keep stale schedules while new chunks get the
corrected schedule, producing an audible seam and occasional underrun at the transition.

This plan executes the recommended three-step path to resolve issue #10 properly, stopping to
test after each step:

1. Fix the staleness hazard (defer timestamp conversion to poll time) — low-risk correctness fix.
2. Re-evaluate burst-then-best / reference-aligned sampling now that large corrections are safe.
3. Implement in-SDK sample insert/drop — the architectural change issue #10 actually asks for.

## Step 1: Defer server→local timestamp conversion to poll time — DONE

Modify `AudioBuffer` ([AudioBuffer.kt](sendspin-protocol/src/main/kotlin/com/sendspin/protocol/AudioBuffer.kt))
so chunks are stored keyed by *server* timestamp and `localPlayTimeMicros` is computed from the
live `ClockSync` estimate only when a chunk is dequeued — not baked in at arrival time. This
removes the stale-schedule hazard described above, independent of which sampling strategy is
used upstream, and unblocks step 2.

1. **Queue key change**: order the queue element (rename `ScheduledChunk` → `QueuedChunk`) by
   `chunk.serverTimestampMicros` instead of a precomputed `localPlayTimeMicros`. Comparator:
   `compareBy { it.chunk.serverTimestampMicros }`.

2. **`offer()`**: still calls `clockSync.toLocalMicros()` once, but only to evaluate the existing
   admission-policy gates (drop-too-late, drop-too-far-future, evict-furthest-future-on-full) —
   these are about whether/which chunks to admit, not how to schedule them, so computing them at
   arrival time remains correct. It then stores the chunk keyed by server timestamp with no baked
   `localPlayTimeMicros`. "Furthest in the future" eviction is redefined as max
   `serverTimestampMicros` (consistent with local-time ordering since `toLocalMicros` is
   monotonic in server time for a fixed offset/drift snapshot).

3. **`poll(nowMicros)`**: peek the head, compute
   `clockSync.toLocalMicros(head.chunk.serverTimestampMicros, nowMicros) - staticDelayMicros`
   live, compare to `nowMicros`, and only then dequeue. This is the core of the fix.

4. **`nextChunkDelayMicros(nowMicros)`**: same live recomputation for the peeked head.

5. Keep `flush()`, `size`, `signalUnderrun()`, `staticDelayMicros`, stats counters unchanged.

**Files**: [AudioBuffer.kt](sendspin-protocol/src/main/kotlin/com/sendspin/protocol/AudioBuffer.kt),
[AudioBufferTest.kt](sendspin-protocol/src/test/kotlin/com/sendspin/protocol/AudioBufferTest.kt).

Update existing ordering/late-drop/eviction/static-delay/underrun tests to account for schedule
times now being computed at poll time. Add a new regression test: offer chunks, then change the
mocked `clockSync.toLocalMicros` to return a shifted value (simulating a clock correction), and
assert `poll`/`nextChunkDelayMicros` immediately reflect the updated schedule for
already-queued chunks — this is the direct test for the bug this step fixes.

**Verify**: `./gradlew :sendspin-protocol:test`. Stop here for review/testing before step 2.

**Result**: Implemented as described — `AudioBuffer` now stores `QueuedChunk(chunk)` ordered by
`serverTimestampMicros`, and `poll`/`nextChunkDelayMicros` compute `localPlayTimeMicros` live via
a new `scheduledMicros()` helper using the current `clockSync` + `staticDelayMicros`. Admission
gates (late/far-future drop, furthest-future eviction) still use the snapshot at arrival time.
Added regression test `clock correction after offer immediately reshapes schedule of queued
chunks` to `AudioBufferTest`. Full `:sendspin-protocol:test` suite passes.

## Step 2: Re-evaluate burst-then-best / reference-aligned sampling — DONE

With step 1 landed, large discrete offset corrections no longer create stale-schedule seams, so
it becomes safe to revisit aligning `ClockSync` consumption with the reference cadence (buffer
all 8 burst replies, feed the filter once per burst from the lowest-RTT sample) if there's a
convergence/accuracy benefit. This also touches the open question of probe cadence — intra-burst
deltas (200ms apart) are too small to usefully constrain the drift term, which is a long-baseline
estimate; the ~8.4s inter-burst gap is where `toLocalMicros`'s `effectiveDrift * elapsed`
extrapolation has to carry the estimate.

Concretely: prototype changing `SendSpinClient`'s burst-reply handling
([SendSpinClient.kt:578-591](sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt#L578),
`BURST_INTERVAL_MS = 10_000L`, `PROBES_PER_BURST = 8`, `PROBE_INTERVAL_MS = 200L`) to buffer
the 8 replies and apply a single filter update from the lowest-RTT sample, and/or experiment with
cadence (spreading probes more evenly, shortening `BURST_INTERVAL_MS`) to improve drift
convergence. Validate against real playback (or recorded traces) that no pops/underruns reappear
now that step 1 removed the staleness hazard, and that offset/drift convergence is equal or
better than the current per-sample approach.

**Files**: [SendSpinClient.kt](sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt) burst-handling code, [ClockSync.kt](sendspin-protocol/src/main/kotlin/com/sendspin/protocol/ClockSync.kt) if cadence/filter tuning is needed.

**Verify**: `./gradlew :sendspin-protocol:test` plus manual/real-device playback testing for
audible artifacts. Stop here for review/testing before step 3.

**Result**: Implemented burst-then-best in `SendSpinClient` — `ServerTime` replies are buffered
in `burstReplies` as they arrive; once `PROBES_PER_BURST` (8) replies have accumulated, the
lowest-RTT sample (via new `rttMicros()` helper using the standard NTP RTT formula) is fed to
`clockSync.processMeasurement()` once, and the buffer is cleared (also cleared in
`clearSessionState()` on reconnect). Probe send cadence (`BURST_INTERVAL_MS` /
`PROBES_PER_BURST` / `PROBE_INTERVAL_MS`) is unchanged — only consumption changed, mirroring the
original failed experiment but now safe because step 1 made `AudioBuffer` recompute schedules
live. `:sendspin-protocol:test` passes (no existing tests exercised `ServerTime` handling in
`SendSpinClient`, so no test updates were needed beyond the build staying green).
**Manual/real-device playback validation for audible artifacts is still needed** — that can't be
done from this environment; please test on-device before merging. Cadence/drift-convergence
tuning (mentioned as an open question) was left as-is since it's an orthogonal concern from the
sampling-strategy change requested here.

## Step 3: In-SDK sample insert/drop

Implement active drift correction at the playback layer — inserting or dropping samples (or
light time-stretching) to continuously correct small accumulated drift in real time, rather than
relying solely on `ClockSync`'s estimate plus passive "sleep until scheduled time" playback. This
is the change issue #10 actually requests, matching sendspin-rs/sendspin-cpp/etc.

This is a larger architectural change: it likely needs a resampling/insertion stage between
decode and output, and changes the playback contract for embedders (currently `AudioPlayer` is a
thin interface — start/stop/configure/flush/transition — implemented by e.g. android-tv's
`SyncedAudioPlayer`, which polls `AudioBuffer` and writes PCM via `AudioTrack.write(...,
WRITE_BLOCKING)` with no sample manipulation today). Design questions to resolve before
implementing:
- Where does the insert/drop logic live — in `sendspin-protocol` (shared, e.g. a decorator around
  `AudioPlayer` or a stage `AudioBuffer` exposes) or pushed to each embedder?
- What signal drives correction — comparing `ClockSync`'s live offset/drift against actual
  playback progress, continuously, rather than only at schedule time?
- How to avoid audible artifacts from the correction itself (e.g. only insert/drop at zero
  crossings or frame boundaries, rate-limit correction magnitude).

Scope the concrete design and file changes once steps 1–2 are settled and validated, since the
right integration point depends on what step 2 reveals about correction frequency/magnitude
needed in practice.

**Verify**: unit tests for the new correction stage plus manual/real-device playback testing.

## Wrap-up

After each step, run `./gradlew :sendspin-protocol:test`, pause for review/manual testing before
proceeding to the next step, and update this plan file to mark the step done and note any
deviations from the plan. Commit this plan file alongside the code changes in the PR.
