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
