package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRequestFormatTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val streamRequestFormatAdapter = moshi.adapter(StreamRequestFormat::class.java)

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(
            AudioFormat("pcm", 2, 48000, 24),
            AudioFormat("pcm", 2, 48000, 16),
        ),
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

    private class CapturingSocket : SendSpinWebSocket {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }
        override fun close(code: Int, reason: String?) {}
    }

    private fun buildClient() = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = defaultPreferences,
        clientId = "test-id",
        clientName = "Test",
        manufacturer = "Acme",
        productName = "TestDevice",
        softwareVersion = "1",
        audioPlayerFactory = noOpPlayerFactory,
    )

    @Test
    fun `serializes only the fields provided`() {
        val json = streamRequestFormatAdapter.toJson(
            StreamRequestFormat(
                payload = StreamRequestFormatPayload(
                    player = PlayerFormatRequest(codec = "pcm", bitDepth = 16)
                )
            )
        )
        assertEquals(
            """{"type":"stream/request-format","payload":{"player":{"codec":"pcm","bit_depth":16}}}""",
            json,
        )
    }

    @Test
    fun `requestPlayerFormat sends stream request-format with the requested fields`() {
        val client = buildClient()
        val socket = CapturingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1")),
        )

        client.requestPlayerFormat(codec = "flac", channels = 1, sampleRate = 8000, bitDepth = 16)

        val sent = socket.sent.map { moshi.adapter(StreamRequestFormat::class.java).fromJson(it) }
        val requestFormatMsg = sent.filterNotNull().firstOrNull { it.type == "stream/request-format" }
        assertTrue("no stream/request-format message was sent", requestFormatMsg != null)
        assertEquals(
            PlayerFormatRequest(codec = "flac", channels = 1, sampleRate = 8000, bitDepth = 16),
            requestFormatMsg!!.payload.player,
        )
    }

    @Test
    fun `requestPlayerFormat is a no-op when disconnected`() {
        val client = buildClient()
        // No socket has been attached; sending should not throw.
        client.requestPlayerFormat(codec = "flac")
        assertNull(client.serverHello.value)
    }
}
