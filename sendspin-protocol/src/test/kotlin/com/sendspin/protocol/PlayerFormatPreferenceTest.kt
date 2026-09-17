package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec PR #195 folded `stream/request-format` into `client/state`: a player reports its preferred
 * format (which MUST be one of its advertised `supported_formats`) via [SendSpinClient.setPlayerFormatPreference]
 * instead of sending a dedicated request message.
 */
class PlayerFormatPreferenceTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

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
        // Background coroutines (clock sync, periodic state reports) call send() concurrently
        // with the test thread reading `sent`, so this must be a thread-safe collection.
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
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
    fun `setPlayerFormatPreference reports the format in client-state`() {
        val client = buildClient()
        val socket = CapturingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1")),
        )
        socket.sent.clear()

        client.setPlayerFormatPreference(AudioFormat("pcm", 2, 48000, 16))

        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .last { it.type == "client/state" }
        assertTrue("client/state should carry a player object", stateMsg.payload.player != null)
        assertTrue(
            "client/state player.format should carry the requested format",
            stateMsg.payload.player!!.format == AudioFormat("pcm", 2, 48000, 16),
        )
    }

    @Test
    fun `setPlayerFormatPreference null clears the preference`() {
        val client = buildClient()
        val socket = CapturingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1")),
        )
        client.setPlayerFormatPreference(AudioFormat("pcm", 2, 48000, 16))
        socket.sent.clear()

        client.setPlayerFormatPreference(null)

        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .last { it.type == "client/state" }
        assertNull("format preference should be cleared", stateMsg.payload.player!!.format)
    }

    @Test
    fun `setPlayerFormatPreference is a no-op crash-wise when disconnected`() {
        val client = buildClient()
        // No socket has been attached; sending should not throw.
        client.setPlayerFormatPreference(AudioFormat("pcm", 2, 48000, 16))
        assertNull(client.serverHello.value)
    }
}
