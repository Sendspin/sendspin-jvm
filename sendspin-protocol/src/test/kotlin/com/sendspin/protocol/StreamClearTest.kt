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

    private fun buildClientWithCapture(flushCount: IntArray): SendSpinClient {
        val factory: (AudioBuffer, ClockSync) -> AudioPlayer = { _, _ ->
            object : AudioPlayer {
                override val isPlaying = false
                override val droppedDecodeFrames = 0L
                override fun configure(format: StreamFormat) {}
                override fun start() {}
                override fun flush() { flushCount[0]++ }
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

    @Test
    fun `stream clear with no roles flushes audio player`() {
        val flushCount = intArrayOf(0)
        val client = buildClientWithCapture(flushCount)
        client.handleTextMessage("""{"type":"stream/clear"}""")
        assertEquals(1, flushCount[0])
    }

    @Test
    fun `stream clear with player role flushes audio player`() {
        val flushCount = intArrayOf(0)
        val client = buildClientWithCapture(flushCount)
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["player"]}}""")
        assertEquals(1, flushCount[0])
    }

    @Test
    fun `stream clear with only visualizer role does not flush audio player`() {
        val flushCount = intArrayOf(0)
        val client = buildClientWithCapture(flushCount)
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["visualizer"]}}""")
        assertEquals(0, flushCount[0])
    }

    @Test
    fun `stream clear with both roles flushes audio player`() {
        val flushCount = intArrayOf(0)
        val client = buildClientWithCapture(flushCount)
        client.handleTextMessage("""{"type":"stream/clear","payload":{"roles":["player","visualizer"]}}""")
        assertEquals(1, flushCount[0])
    }
}
