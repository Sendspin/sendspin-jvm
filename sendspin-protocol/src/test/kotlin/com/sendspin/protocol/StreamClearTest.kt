package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamClearTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private fun buildClient(): SendSpinClient {
        val factory: (AudioBuffer, ClockSync) -> AudioPlayer = { _, _ ->
            object : AudioPlayer {
                override val isPlaying = false
                override val droppedDecodeFrames = 0L
                override fun configure(format: StreamFormat) {}
                override fun start() {}
                override fun flushSink() {}
                override fun stop() {}
                override fun transition(format: StreamFormat) {}
                override fun setVolume(gain: Float) {}
            }
        }
        return SendSpinClient(
            okHttpClient = OkHttpClient.Builder().build(),
            moshi = moshi,
            preferences = defaultPreferences,
            clientId = "test-id",
            clientName = "Test",
            manufacturer = "Acme",
            productName = "TestDevice",
            softwareVersion = "1",
            audioPlayerFactory = factory,
        )
    }

    /** A fresh ClockSync has offset 0, so a "now" server timestamp is admitted, not dropped. */
    private fun SendSpinClient.bufferOneChunk() {
        audioBuffer.offer(AudioChunk(ClockSync.localMicros(), ByteArray(4)))
        assertEquals("precondition: the chunk must be buffered", 1, audioBuffer.size)
    }

    @Test
    fun `stream clear with no roles clears the player buffer`() {
        val client = buildClient()
        client.bufferOneChunk()
        client.handleTextMessage("""{"type":"stream/clear"}""")
        assertEquals(0, client.audioBuffer.size)
    }

    @Test
    fun `stream clear with player role clears the player buffer`() {
        val client = buildClient()
        client.bufferOneChunk()
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["player"]}}""")
        assertEquals(0, client.audioBuffer.size)
    }

    @Test
    fun `stream clear with only visualizer role leaves the player buffer intact`() {
        val client = buildClient()
        client.bufferOneChunk()
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["visualizer"]}}""")
        assertEquals(1, client.audioBuffer.size)
    }

    @Test
    fun `stream clear with both roles clears the player buffer`() {
        val client = buildClient()
        client.bufferOneChunk()
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["player","visualizer"]}}""")
        assertEquals(0, client.audioBuffer.size)
    }
}
