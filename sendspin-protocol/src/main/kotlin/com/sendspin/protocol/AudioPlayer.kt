package com.sendspin.protocol

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    fun configure(format: StreamFormat)
    fun start()
    /**
     * Drain the hardware sink so nothing already handed to the audio device keeps playing.
     *
     * MUST NOT clear the library's [AudioBuffer]. [SendSpinClient] clears that itself, synchronously
     * and in wire order, so that a clear lands strictly between the last old-stream chunk and the
     * first new-stream chunk. An implementation that also cleared the buffer would run later, on the
     * audio thread, and wipe new-stream chunks that were already offered.
     */
    fun flushSink()
    fun stop()
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
    override fun flushSink() {}
    override fun stop() {}
    override fun transition(format: StreamFormat) {}
    override fun setVolume(gain: Float) {}
}
