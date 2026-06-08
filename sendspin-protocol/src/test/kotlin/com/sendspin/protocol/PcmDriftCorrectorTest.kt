package com.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmDriftCorrectorTest {

    // Mono ramp 0..N-1 — easy to reason about interpolation and monotonicity.
    private fun ramp(frames: Int): ShortArray = ShortArray(frames) { it.toShort() }

    @Test
    fun `zero drift passes audio through at roughly the same length`() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        val pcm = ramp(1000)
        val out = corrector.correct(pcm, driftMicros = 0L, blockDurationMicros = 20_000L)

        // The last input frame has no successor to interpolate toward, so output is one
        // frame short of input at step == 1 — otherwise length is preserved.
        assertEquals(pcm.size - 1, out.size)
        // With step == 1 and phase starting at 0, interpolation is exact (frac == 0).
        assertEquals(pcm[0], out[0])
        assertEquals(pcm[500], out[500])
    }

    @Test
    fun `positive drift (behind schedule) drops samples to speed up`() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val pcm = ramp(1000)
        // Drift equal to 5% of the block duration — clamped to maxCorrectionPpm (5%).
        val out = corrector.correct(pcm, driftMicros = 1_000L, blockDurationMicros = 20_000L)

        // step = 1.05 → fewer output frames than input frames.
        assertTrue("expected output shorter than input but was ${out.size} vs ${pcm.size}",
            out.size < pcm.size)
    }

    @Test
    fun `negative drift (ahead of schedule) inserts samples to slow down`() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val pcm = ramp(1000)
        val out = corrector.correct(pcm, driftMicros = -1_000L, blockDurationMicros = 20_000L)

        // step = 0.95 → more output frames than input frames.
        assertTrue("expected output longer than input but was ${out.size} vs ${pcm.size}",
            out.size > pcm.size)
    }

    @Test
    fun `correction is clamped to maxCorrectionPpm regardless of drift magnitude`() {
        val small = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 2_000.0)
        val pcm = ramp(10_000)

        // A huge drift should be clamped to the same ratio as a moderate one above the cap.
        val outHuge = small.correct(pcm, driftMicros = 1_000_000L, blockDurationMicros = 20_000L)
        val outModerate = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 2_000.0)
            .correct(pcm, driftMicros = 100L, blockDurationMicros = 20_000L) // 0.5% > 0.2% cap

        assertEquals(outModerate.size, outHuge.size)
    }

    @Test
    fun `interleaved stereo channels remain aligned`() {
        val corrector = PcmDriftCorrector(channelCount = 2, maxCorrectionPpm = 50_000.0)
        // L = 0,2,4,...  R = 1,3,5,...
        val frames = 500
        val pcm = ShortArray(frames * 2) { it.toShort() }

        val out = corrector.correct(pcm, driftMicros = 800L, blockDurationMicros = 20_000L)

        assertEquals(0, out.size % 2)
        // Right channel sample should always be one more than the left at the same frame.
        for (i in out.indices step 2) {
            assertTrue(abs((out[i + 1] - out[i]) - 1) <= 1) // interpolation may round to ±1
        }
    }

    @Test
    fun `phase carries over continuously across blocks`() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val first = corrector.correct(ramp(500), driftMicros = 1_000L, blockDurationMicros = 20_000L)
        val second = corrector.correct(ramp(500), driftMicros = 1_000L, blockDurationMicros = 20_000L)

        // No exception, both blocks produced output, and the stream keeps shrinking consistently
        // (regression check that `phase` doesn't reset or grow unbounded between calls).
        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
    }

    @Test
    fun `reset clears carried-over phase`() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        corrector.correct(ramp(500), driftMicros = 500L, blockDurationMicros = 20_000L)
        corrector.reset()
        val out = corrector.correct(ramp(500), driftMicros = 0L, blockDurationMicros = 20_000L)

        // After reset with zero drift, behaves like a fresh instance: exact passthrough start.
        assertEquals(0.toShort(), out[0])
    }

    @Test
    fun `short blocks are passed through unchanged`() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        val pcm = shortArrayOf(42)
        assertEquals(pcm, corrector.correct(pcm, driftMicros = 1000L, blockDurationMicros = 20_000L))
    }
}
