package com.sendspin.protocol

import kotlin.math.min

/**
 * Continuously corrects small playback-clock drift by gently resampling interleaved 16-bit PCM —
 * inserting or dropping samples via linear interpolation — rather than relying solely on
 * [ClockSync]'s estimate plus passive "sleep until scheduled time" playback.
 *
 * This is the in-SDK active-correction mechanism issue #10 asks for, matching peer SDKs
 * (sendspin-rs/sendspin-cpp/etc). Embedders feed each block of decoded PCM through [correct]
 * along with the *current* drift (how far ahead/behind real-time playback is from its scheduled
 * position, e.g. `scheduledLocalMicros - actualPlaybackPositionMicros`, both derived from the
 * live [ClockSync] estimate). The corrector nudges the effective playback rate by at most
 * [maxCorrectionPpm] parts-per-million — far below the threshold of audible pitch change — so
 * drift is absorbed smoothly over many blocks instead of in a single discrete jump.
 *
 * Linear interpolation between adjacent samples avoids the clicks/pops that naive sample
 * duplication or truncation would introduce; the fractional read position ([phase]) carries over
 * between calls so correction is continuous across block boundaries.
 *
 * One instance should be used per continuous playback stream (reset via [reset] on flush/seek/
 * stream transition, since the carried-over fractional read position no longer corresponds to
 * a meaningful position in the new stream).
 */
class PcmDriftCorrector(
    private val channelCount: Int,
    private val maxCorrectionPpm: Double = 2_000.0, // ±0.2% — inaudible as a pitch/speed change
) {
    init {
        require(channelCount > 0) { "channelCount must be positive" }
        require(maxCorrectionPpm >= 0.0) { "maxCorrectionPpm must not be negative" }
    }

    // Fractional read position into the most recent input block, carried across calls so the
    // resampled stream remains continuous (no gap or overlap at block boundaries).
    private var phase = 0.0

    /**
     * Resamples [pcm] (interleaved 16-bit samples, [channelCount] channels) to nudge playback
     * speed toward eliminating [driftMicros] of accumulated offset, assuming the block spans
     * [blockDurationMicros] of audio at the nominal rate.
     *
     * @param driftMicros positive when local playback is *behind* schedule (audio should speed
     *   up slightly — net effect: samples are gradually dropped); negative when *ahead* (audio
     *   should slow down slightly — net effect: samples are gradually inserted/interpolated in).
     * @param blockDurationMicros nominal duration of [pcm] at the stream's sample rate; used to
     *   scale how aggressively this block corrects toward eliminating the drift.
     * @return resampled PCM (interleaved 16-bit, same channel layout); length varies slightly
     *   from the input depending on the correction direction.
     */
    fun correct(pcm: ShortArray, driftMicros: Long, blockDurationMicros: Long): ShortArray {
        val frameCount = pcm.size / channelCount
        if (frameCount < 2 || blockDurationMicros <= 0L) return pcm

        // Fraction of the block's duration the drift represents, clamped to the configured
        // max correction rate so a single noisy reading can't cause an audible speed change.
        val rawFraction = driftMicros.toDouble() / blockDurationMicros.toDouble()
        val maxFraction = maxCorrectionPpm / 1_000_000.0
        val correctionFraction = rawFraction.coerceIn(-maxFraction, maxFraction)

        // step > 1 reads input faster than output is produced (net: samples dropped, catching up);
        // step < 1 reads input slower (net: samples interpolated in, slowing down).
        val step = 1.0 + correctionFraction

        val lastFrame = frameCount - 1
        val out = ArrayList<Short>(((frameCount / step) * channelCount).toInt() + channelCount)
        var pos = phase
        while (pos < lastFrame) {
            val idx = pos.toInt()
            val frac = pos - idx
            val base = idx * channelCount
            for (ch in 0 until channelCount) {
                val a = pcm[base + ch]
                val b = pcm[base + channelCount + ch]
                out.add((a + (b - a) * frac).toInt().toShort())
            }
            pos += step
        }

        // Carry the overshoot into the next block. Given frameCount >= 2 and phase always in
        // [0, 1], `pos - lastFrame` is mathematically guaranteed to land in [0, step] <= [0, 1]
        // at this point — the clamp is purely defensive against a future change to the loop
        // bounds or step range silently breaking that invariant (which would otherwise corrupt
        // `pos.toInt()` indexing on the next call in a hard-to-diagnose way).
        phase = min(pos - lastFrame, 1.0).coerceAtLeast(0.0)

        return out.toShortArray()
    }

    /** Reset correction state — call on flush/seek/stream transition. */
    fun reset() {
        phase = 0.0
    }
}
