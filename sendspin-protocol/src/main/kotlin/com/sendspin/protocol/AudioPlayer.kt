package com.sendspin.protocol

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    fun configure(format: StreamFormat)
    fun start()
    fun flush()
    fun stop()
    fun transition(format: StreamFormat)
}
