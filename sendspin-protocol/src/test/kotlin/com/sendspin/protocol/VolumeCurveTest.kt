package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeCurveTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private fun buildClientWithCapture(gainCapture: MutableList<Float>): SendSpinClient {
        val factory: (AudioBuffer, ClockSync) -> AudioPlayer = { _, _ ->
            object : AudioPlayer {
                override val isPlaying = false
                override val droppedDecodeFrames = 0L
                override fun configure(format: StreamFormat) {}
                override fun start() {}
                override fun flush() {}
                override fun stop() {}
                override fun transition(format: StreamFormat) {}
                override fun setVolume(gain: Float) { gainCapture += gain }
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
    fun `volume 100 unmuted yields gain 1`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":100,"muted":false}}}""")
        assertEquals(1, gains.size)
        assertEquals(1.0f, gains[0], 0.001f)
    }

    @Test
    fun `volume 0 unmuted yields gain 0`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":0,"muted":false}}}""")
        assertEquals(1, gains.size)
        assertEquals(0.0f, gains[0], 0.001f)
    }

    @Test
    fun `volume 50 unmuted yields perceptual curve gain`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":50,"muted":false}}}""")
        val expected = (50 / 100.0).pow(1.5).toFloat()
        assertEquals(1, gains.size)
        assertEquals(expected, gains[0], 0.001f)
    }

    @Test
    fun `muted yields gain 0 regardless of volume`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":80,"muted":true}}}""")
        assertEquals(1, gains.size)
        assertEquals(0.0f, gains[0], 0.001f)
    }
}
