package com.sendspin.protocol

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    fun configure(format: StreamFormat)
    fun start()
    fun flush()
    fun stop()

    /**
     * Applies an in-place format change from a `stream/start` received while already playing
     * (e.g. the server switching codec/sample-rate for the current track). Per spec (roles/player/
     * v1.md, PR #283): this does NOT end or clear the stream — implementations MUST NOT flush
     * already-buffered chunks or resend audio. The format change lands at a chunk boundary on the
     * existing timeline; chunks already queued were encoded in the previous format and MUST be
     * decoded as such, while chunks arriving after this call use [format]. A player that cannot
     * switch formats gaplessly should have advertised a single `sample_rate`/`channels` so the
     * server resamples for it instead of sending a format change.
     */
    fun transition(format: StreamFormat)
    /** Apply a linear gain in [0.0, 1.0] derived from the perceptual volume curve. */
    fun setVolume(gain: Float)
}

/** Discards every call. Used when a host does not advertise [OptionalRole.PLAYER]. */
object NoOpAudioPlayer : AudioPlayer {
    override val isPlaying = false
    override val droppedDecodeFrames = 0L
    override fun configure(format: StreamFormat) {}
    override fun start() {}
    override fun flush() {}
    override fun stop() {}
    override fun transition(format: StreamFormat) {}
    override fun setVolume(gain: Float) {}
}
