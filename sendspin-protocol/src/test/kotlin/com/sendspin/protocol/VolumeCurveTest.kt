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
    fun `server command volume 100 yields gain 1`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":100}}}""")
        assertEquals(1, gains.size)
        assertEquals(1.0f, gains[0], 0.001f)
    }

    @Test
    fun `server command volume 0 yields gain 0`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":0}}}""")
        assertEquals(1, gains.size)
        assertEquals(0.0f, gains[0], 0.001f)
    }

    @Test
    fun `server command volume 50 yields perceptual curve gain`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":50}}}""")
        val expected = (50 / 100.0).pow(1.5).toFloat()
        assertEquals(1, gains.size)
        assertEquals(expected, gains[0], 0.001f)
    }

    @Test
    fun `server command mute yields gain 0 regardless of volume`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":80}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}""")
        assertEquals(2, gains.size)
        assertEquals(0.0f, gains[1], 0.001f)
    }

    @Test
    fun `unmuting restores previous volume curve gain`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":80}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":false}}}""")
        val expected = (80 / 100.0).pow(1.5).toFloat()
        assertEquals(3, gains.size)
        assertEquals(expected, gains[2], 0.001f)
    }

    @Test
    fun `out-of-range volume is clamped to 0-100`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":150}}}""")
        assertEquals(1, gains.size)
        assertEquals(1.0f, gains[0], 0.001f)
    }

    @Test
    fun `volume command missing volume field is ignored`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume"}}}""")
        assertEquals(0, gains.size)
    }

    @Test
    fun `mute command missing mute field is ignored`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute"}}}""")
        assertEquals(0, gains.size)
    }

    @Test
    fun `set_static_delay command applies delay to audio buffer`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"set_static_delay","static_delay_ms":250}}}""")
        assertEquals(250_000L, client.audioBuffer.staticDelayMicros)
        // Volume command path is unaffected by a static-delay command.
        assertEquals(0, gains.size)
    }

    @Test
    fun `group update volume does not affect player gain`() {
        val gains = mutableListOf<Float>()
        val client = buildClientWithCapture(gains)
        client.handleTextMessage("""{"type":"group/update","payload":{"volume":30,"muted":false}}""")
        assertEquals(0, gains.size)
        assertEquals(30, client.controllerState.value?.volume)
    }
}
