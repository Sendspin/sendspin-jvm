package com.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClockSyncTest {

    private lateinit var clockSync: ClockSync

    @Before
    fun setUp() {
        clockSync = ClockSync()
    }

    @Test
    fun `offset converges after multiple measurements with constant offset`() {
        val trueOffsetMicros = 50_000L // 50 ms server-ahead
        val rttMicros = 4_000L         // 4 ms round-trip

        repeat(20) {
            val t1 = 1_000_000L + it * 100_000L
            val t2 = t1 + trueOffsetMicros + rttMicros / 2
            val t3 = t2 + 100L
            val t4 = t1 + rttMicros + 100L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        // After 20 probes the estimate should be within 1 ms of the truth
        val estimatedOffset = clockSync.lastOffsetMicros
        assertTrue(
            "Offset should converge near $trueOffsetMicros µs but was $estimatedOffset",
            kotlin.math.abs(estimatedOffset - trueOffsetMicros) < 2_000.0
        )
    }

    @Test
    fun `toLocalMicros subtracts offset from server timestamp`() {
        val trueOffsetMicros = 20_000L
        val rttMicros = 2_000L
        repeat(15) {
            val t1 = 500_000L + it * 50_000L
            val t2 = t1 + trueOffsetMicros + rttMicros / 2
            val t3 = t2 + 50L
            val t4 = t1 + rttMicros + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        val serverTimestamp = 2_000_000L
        val localTimestamp = clockSync.toLocalMicros(serverTimestamp)

        // Local ≈ server − offset
        val expected = serverTimestamp - trueOffsetMicros
        assertTrue(
            "Local time should be near $expected but was $localTimestamp",
            kotlin.math.abs(localTimestamp - expected) < 3_000L
        )
    }

    @Test
    fun `single measurement moves offset away from zero`() {
        val initialOffset = clockSync.offsetMicros
        assertEquals(0L, initialOffset)

        clockSync.processMeasurement(
            t1 = 1_000_000L,
            t2 = 1_010_000L, // +10 ms (server ahead)
            t3 = 1_010_100L,
            t4 = 1_002_100L,
        )

        // Offset should have moved toward ~10 ms
        assertTrue(clockSync.offsetMicros > 0)
    }

    @Test
    fun `calculateProgress returns base when paused`() {
        assertEquals(12_345L, ClockSync.calculateProgress(12_345L, 0L, 0, 60_000L, nowMicros = 999_999L))
    }

    @Test
    fun `calculateProgress extrapolates forward at normal speed`() {
        // base=10000 ms, 5 s elapsed at 1x → 10000 + 5000 = 15000 ms
        val result = ClockSync.calculateProgress(10_000L, 0L, 1000, 0L, nowMicros = 5_000_000L)
        assertEquals(15_000L, result)
    }

    @Test
    fun `calculateProgress extrapolates at 1_5x speed`() {
        // base=0 ms, 10 s elapsed at 1.5x → 0 + 15000 = 15000 ms
        val result = ClockSync.calculateProgress(0L, 0L, 1500, 0L, nowMicros = 10_000_000L)
        assertEquals(15_000L, result)
    }

    @Test
    fun `calculateProgress clamps to track duration`() {
        // base=55000 ms, 10 s elapsed at 1x → raw=65000, clamped to 60000
        val result = ClockSync.calculateProgress(55_000L, 0L, 1000, 60_000L, nowMicros = 10_000_000L)
        assertEquals(60_000L, result)
    }

    @Test
    fun `calculateProgress clamps to zero when result goes negative`() {
        // base=1000 ms, base time is 5 s in the future → raw = 1000 - 5000 = -4000, clamped to 0
        val result = ClockSync.calculateProgress(1_000L, 5_000_000L, 1000, 0L, nowMicros = 0L)
        assertEquals(0L, result)
    }

    @Test
    fun `calculateProgress does not clamp upper bound when duration is zero`() {
        // live stream: no upper clamp regardless of large progress value
        val result = ClockSync.calculateProgress(1_000_000L, 0L, 1000, 0L, nowMicros = 0L)
        assertEquals(1_000_000L, result)
    }

    @Test
    fun `high RTT measurements are incorporated but with less weight`() {
        val trueOffset = 5_000L
        val normalRtt = 2_000L
        val highRtt = 100_000L

        // Establish baseline with low-RTT probes
        repeat(10) {
            val t1 = it * 10_000L
            clockSync.processMeasurement(t1, t1 + trueOffset + normalRtt / 2, t1 + trueOffset + normalRtt / 2 + 10, t1 + normalRtt + 10)
        }
        val baselineOffset = clockSync.lastOffsetMicros

        // One noisy high-RTT probe should not destroy convergence
        clockSync.processMeasurement(0, trueOffset + highRtt / 2, trueOffset + highRtt / 2 + 10, highRtt + 10)

        // Should still be within 3 ms of baseline
        assertTrue(kotlin.math.abs(clockSync.lastOffsetMicros - baselineOffset) < 3_000.0)
    }

    @Test
    fun `4ms RTT converges as tightly as the 1ms convergence test`() {
        // RTT=4000µs → R = (4000 * 0.5 / 2)^2 = (1000µs)^2 = 1e6, identical to the
        // old hardcoded measurementNoise. The existing convergence test uses this same RTT,
        // so we expect the same tight tolerance (< 2ms after 20 probes).
        val trueOffset = 30_000L
        val rtt = 4_000L

        repeat(20) {
            val t1 = 1_000_000L + it * 100_000L
            val t2 = t1 + trueOffset + rtt / 2
            val t3 = t2 + 100L
            val t4 = t1 + rtt + 100L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        assertTrue(
            "Expected convergence within 2ms for RTT=4ms (same R as old fixed noise), was ${clockSync.lastOffsetMicros}",
            kotlin.math.abs(clockSync.lastOffsetMicros - trueOffset) < 2_000.0,
        )
    }

    @Test
    fun `low RTT sample converges faster than high RTT sample`() {
        // R = (rtt/4)^2, so a lower-RTT sample carries more weight. After the same number
        // of identical-offset probes, the low-RTT filter should be closer to the truth.
        val lowRttClock  = ClockSync()
        val highRttClock = ClockSync()
        val trueOffset = 40_000L
        val lowRtt  = 1_000L   // R = (250µs)^2
        val highRtt = 8_000L   // R = (2000µs)^2 — 64× larger, so much less confident

        repeat(5) {
            val t1 = 1_000_000L + it * 100_000L
            fun probe(rtt: Long) = Triple(t1 + trueOffset + rtt / 2, t1 + trueOffset + rtt / 2 + 50L, t1 + rtt + 50L)
            val (t2l, t3l, t4l) = probe(lowRtt)
            val (t2h, t3h, t4h) = probe(highRtt)
            lowRttClock.processMeasurement(t1, t2l, t3l, t4l)
            highRttClock.processMeasurement(t1, t2h, t3h, t4h)
        }

        val lowRttError  = kotlin.math.abs(lowRttClock.lastOffsetMicros  - trueOffset)
        val highRttError = kotlin.math.abs(highRttClock.lastOffsetMicros - trueOffset)
        assertTrue(
            "Low-RTT filter (error=$lowRttError µs) should converge faster than high-RTT (error=$highRttError µs)",
            lowRttError < highRttError,
        )
    }

    // ── toLocalMicros drift gate ──────────────────────────────────────────────

    @Test
    fun `toLocalMicros result does not vary with elapsed time when drift SNR is low`() {
        // After a few measurements with constant offset, xDrift ≈ 0 and SNR < threshold.
        // toLocalMicros should ignore elapsed time (effectiveDrift = 0).
        val trueOffset = 30_000L
        repeat(10) { i ->
            val t1 = 1_000_000L + i * 200_000L
            val rtt = 2_000L
            val t2 = t1 + trueOffset + rtt / 2
            val t3 = t2 + 50L
            val t4 = t1 + rtt + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }
        val lastT4 = 1_000_000L + 9 * 200_000L + 2_000L + 50L
        val serverTs = lastT4 + trueOffset
        val atT4 = clockSync.toLocalMicros(serverTs, lastT4)
        val later = clockSync.toLocalMicros(serverTs, lastT4 + 5_000_000L)
        assertEquals(
            "drift gate should suppress elapsed-time drift when SNR is low (xDrift ≈ 0)",
            atT4, later,
        )
    }

    @Test
    fun `toLocalMicros extrapolates drift forward once drift estimate converges`() {
        // Simulate a 1000 ppm clock drift: trueOffset grows by 1 µs per 1000 µs elapsed.
        // After enough measurements the filter's xDrift converges and SNR crosses the gate
        // threshold, so toLocalMicros(serverTs, laterNow) should differ from
        // toLocalMicros(serverTs, t4) by roughly xDrift × elapsed ≈ 1000 µs.
        val trueOffset = 30_000L
        val trueDrift = 1_000e-6  // 1000 ppm (µs offset per µs elapsed)
        val rtt = 2_000L
        repeat(100) { i ->
            val t1 = 1_000_000L + i * 200_000L
            val offsetAtT1 = trueOffset + (trueDrift * t1).toLong()
            val t2 = t1 + offsetAtT1 + rtt / 2
            val t3 = t2 + 50L
            val t4 = t1 + rtt + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }
        val lastT4 = 1_000_000L + 99 * 200_000L + rtt + 50L
        val serverTs = lastT4 + trueOffset + (trueDrift * lastT4).toLong()
        val elapsed = 1_000_000L  // 1 second
        val atT4 = clockSync.toLocalMicros(serverTs, lastT4)
        val later = clockSync.toLocalMicros(serverTs, lastT4 + elapsed)
        // atT4 > later because a larger offset (from drift) makes the local time smaller
        val driftDelta = (atT4 - later).toDouble()
        assertTrue(
            "Expected drift delta ≈ ${(trueDrift * elapsed).toLong()} µs, got $driftDelta µs",
            kotlin.math.abs(driftDelta - trueDrift * elapsed) < 500.0,
        )
    }
}
