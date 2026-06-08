package com.sendspin.protocol

import com.sendspin.protocol.ProtocolLog as Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * NTP-style clock synchronisation with a 2D Kalman filter.
 *
 * Tracks two state variables:
 *  - **offset** (µs): how much to add to a local System.nanoTime()-derived µs value to
 *    produce a server µs timestamp.
 *  - **drift** (µs per µs, dimensionless): rate at which the offset drifts per local µs elapsed.
 *
 * Measurement model: each round-trip gives us an estimate of the offset via the
 * classic formula  offset = ((t2 - t1) + (t3 - t4)) / 2  where t1..t4 are the four
 * NTP timestamps (all in µs, server-clock for t2/t3, client-clock for t1/t4).
 *
 * Callers should send a burst of probes and call [processMeasurement] for each response;
 * the RTT-based measurement noise `R = (rtt/4)²` naturally down-weights high-latency samples.
 * Call [toLocalMicros] to convert server timestamps for audio chunk scheduling.
 */
class ClockSync {

    // ── Kalman state ──────────────────────────────────────────────────────────

    // State: [offset (µs), drift (µs/µs)]
    private var xOffset = 0.0   // µs
    private var xDrift = 0.0    // µs/µs (≈ ppm / 1_000_000)

    // Covariance matrix P  (2×2, stored as four doubles: p00, p01, p10, p11)
    private var p00 = 1e12; private var p01 = 0.0
    private var p10 = 0.0;  private var p11 = 1e-6

    // Timestamp of the last Kalman prediction step (µs, local clock)
    private var lastPredictTimeMicros: Long = localMicros()

    // Sample counter — adaptive forgetting only activates after min_samples
    private var sampleCount = 0

    // ── Filter constants (reference recommended values) ───────────────────────

    // Process noise: no offset random-walk; drift wanders at 1e-11 (µs/µs)/√µs
    private val driftProcessVariance = 1e-22   // (drift_process_std_dev)^2 = (1e-11)^2

    // Measurement noise: scale RTT/2 by max_error_scale before squaring → R = (rtt/4)^2
    private val maxErrorScale = 0.5

    // Adaptive forgetting: inflate P when innovation exceeds cutoff × filter error
    private val adaptiveCutoff = 3.0
    private val forgetFactor = 2.0
    private val minSamplesForForgetting = 100

    // Drift significance: only extrapolate drift in toLocalMicros when SNR is reliable
    private val driftSignificanceThreshold = 2.0

    // For diagnostics
    @Volatile var lastOffsetMicros: Double = 0.0; private set
    @Volatile var lastDriftPpm: Double = 0.0; private set
    @Volatile var lastRttMicros: Long = 0L; private set

    private val lock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed a [ServerTime] measurement into the filter.
     *
     * Call this for every probe response. Low-RTT samples automatically receive more weight
     * through the smaller measurement variance `R = (rtt/4)²`.
     *
     * @param t1 client send time (local µs)
     * @param t2 server receive time (server µs)
     * @param t3 server send time (server µs)
     * @param t4 client receive time (local µs)
     */
    fun processMeasurement(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = rttMicros(t1, t2, t3, t4)
        val offsetEstimate = ((t2 - t1) + (t3 - t4)) / 2.0
        // Measurement variance: (rtt/2 × max_error_scale)^2
        val scaledError = rtt * maxErrorScale / 2.0
        val measurementVariance = scaledError * scaledError

        synchronized(lock) {
            lastRttMicros = rtt
            predict(t4)
            update(offsetEstimate, measurementVariance)
            lastOffsetMicros = xOffset
            lastDriftPpm = xDrift * 1_000_000.0
        }

        Timber.d("ClockSync: offset=%.2f ms  drift=%.3f ppm  rtt=%d µs",
            xOffset / 1000.0, xDrift * 1_000_000.0, rtt)
    }

    /**
     * Convert a server-clock timestamp (µs) to a local-clock timestamp (µs).
     *
     * Extrapolates the offset forward using drift since the last filter update, but only
     * when the drift estimate is statistically significant (SNR ≥ [driftSignificanceThreshold]).
     */
    fun toLocalMicros(serverMicros: Long, nowMicros: Long = localMicros()): Long {
        synchronized(lock) {
            val driftSnr = if (p11 > 0.0) abs(xDrift) / sqrt(p11) else 0.0
            val effectiveDrift = if (driftSnr >= driftSignificanceThreshold) xDrift else 0.0
            val elapsed = nowMicros - lastPredictTimeMicros
            val currentOffset = xOffset + effectiveDrift * elapsed
            return (serverMicros - currentOffset).toLong()
        }
    }

    /** Current best estimate of the clock offset in microseconds (server − local). */
    val offsetMicros: Long get() = synchronized(lock) { xOffset.toLong() }

    // ── Kalman internals ─────────────────────────────────────────────────────

    private fun predict(nowMicros: Long) {
        val dt = (nowMicros - lastPredictTimeMicros).coerceAtLeast(0L).toDouble()
        lastPredictTimeMicros = nowMicros

        // State transition: offset += drift * dt
        xOffset += xDrift * dt

        // P = F * P * F^T + Q
        //   F = [[1, dt], [0, 1]]
        //   Q = diag(0, driftProcessVariance * dt)  — no offset random-walk (process_std_dev=0)
        val q11Dt = driftProcessVariance * dt
        val p00New = p00 + dt * p10 + dt * p01 + dt * dt * p11
        val p01New = p01 + dt * p11
        val p10New = p10 + dt * p11
        val p11New = p11 + q11Dt
        p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
    }

    private fun update(offsetEstimate: Double, measurementVariance: Double) {
        // Adaptive forgetting: inflate P when innovation is large, to recover from disruptions.
        // Only activates after min_samples to avoid false triggers during initial convergence.
        if (sampleCount >= minSamplesForForgetting) {
            val filterError = sqrt(p00)
            if (abs(offsetEstimate - xOffset) > adaptiveCutoff * filterError) {
                val forget = forgetFactor * forgetFactor
                p00 *= forget; p01 *= forget; p10 *= forget; p11 *= forget
            }
        }

        // H = [1, 0]  (we measure offset only)
        val s = p00 + measurementVariance
        val k0 = p00 / s
        val k1 = p10 / s

        val innovation = offsetEstimate - xOffset
        xOffset += k0 * innovation
        xDrift  += k1 * innovation

        // P = (I - K*H) * P — snapshot pre-update values to avoid order-dependency
        val p00Pre = p00; val p01Pre = p01
        p00 = (1 - k0) * p00Pre
        p01 = (1 - k0) * p01Pre
        p10 = p10 - k1 * p00Pre
        p11 = p11 - k1 * p01Pre

        sampleCount++
    }

    companion object {
        fun localMicros(): Long = System.nanoTime() / 1_000L

        /**
         * Round-trip time (µs) for an NTP-style four-timestamp probe:
         * `(t4 - t1) - (t3 - t2)`, i.e. total elapsed client time minus server processing time.
         *
         * Exposed so callers selecting among several probe replies (e.g. "burst-then-best"
         * sampling) can rank them by the same RTT definition [processMeasurement] uses
         * internally for its measurement-noise model.
         */
        fun rttMicros(t1: Long, t2: Long, t3: Long, t4: Long): Long = (t4 - t1) - (t3 - t2)

        /**
         * Computes current track progress (ms) using the spec formula:
         * `track_progress + (now - base_time) * playback_speed / 1_000_000`
         *
         * @param baseProgressMs track position in ms at the snapshot time
         * @param baseLocalTimeMicros local clock (µs) when [baseProgressMs] was valid
         * @param playbackSpeed speed × 1000; 0 = paused
         * @param durationMs total duration in ms; 0 = unknown (live stream, no upper clamp)
         * @param nowMicros current local clock time; defaults to [localMicros]
         */
        fun calculateProgress(
            baseProgressMs: Long,
            baseLocalTimeMicros: Long,
            playbackSpeed: Int,
            durationMs: Long,
            nowMicros: Long = localMicros(),
        ): Long {
            val raw = if (playbackSpeed != 0) {
                baseProgressMs + (nowMicros - baseLocalTimeMicros) * playbackSpeed / 1_000_000L
            } else {
                baseProgressMs
            }
            return raw.coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        }
    }
}

/** Probe burst configuration. */
object ClockSyncConfig {
    const val PROBES_PER_BURST = 8
    const val PROBE_INTERVAL_MS = 200L
    const val BURST_INTERVAL_MS = 10_000L
}
