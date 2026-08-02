package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards that [SendSpinClient] resets its long-lived [ClockSync] when a session ends, so a reconnect
 * (potentially to a different server clock) re-seeds the filter instead of dragging stale Kalman state
 * into the new session. Without the `clockSync.reset()` call in `clearSessionState()`, only [ClockSync]'s
 * own unit test would notice — this test fails if the client-side wiring is dropped.
 */
class SendSpinClientSessionResetTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val preferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("pcm", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private val noOpPlayerFactory: (AudioBuffer, ClockSync) -> AudioPlayer = { _, _ ->
        object : AudioPlayer {
            override val isPlaying = false
            override val droppedDecodeFrames = 0L
            override fun configure(format: StreamFormat) {}
            override fun start() {}
            override fun flush() {}
            override fun stop() {}
            override fun transition(format: StreamFormat) {}
            override fun setVolume(gain: Float) {}
        }
    }

    private fun buildClient() = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        clientId = "test-id",
        clientName = "Test TV",
        manufacturer = "Acme",
        productName = "SmartTV-9000",
        softwareVersion = "14",
        macAddress = null,
        audioPlayerFactory = noOpPlayerFactory,
    )

    @Test
    fun `disconnect resets clockSync so a converged offset does not survive into the next session`() {
        val client = buildClient()

        // Converge the filter on a non-zero offset (never connecting a real socket).
        val trueOffset = 50_000L
        val rtt = 4_000L
        repeat(20) {
            val t1 = 1_000_000L + it * 100_000L
            client.clockSync.processMeasurement(t1, t1 + trueOffset + rtt / 2, t1 + trueOffset + rtt / 2 + 100L, t1 + rtt + 100L)
        }
        assertNotEquals("precondition: filter should have converged off zero", 0L, client.clockSync.offsetMicros)

        // disconnect() runs clearSessionState(), which must reset ClockSync.
        client.disconnect()

        assertEquals("clockSync must be reset when the session ends", 0L, client.clockSync.offsetMicros)
    }
}
