package com.sendspin.protocol

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    fun configure(format: StreamFormat)
    fun start()
    fun flush()
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
    override fun flush() {}
    override fun stop() {}
    override fun transition(format: StreamFormat) {}
    override fun setVolume(gain: Float) {}
}
