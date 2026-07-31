package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per spec `client/state`, the `player` object is only sent when `player` is in the
 * server-declared `active_roles`. Regression coverage for a conformance-harness finding
 * ("non-compliant client: client/state carried a player object for an inactive role").
 */
class ClientStatePlayerRoleTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
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

    private class RecordingSocket : SendSpinWebSocket {
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

    private fun firstClientStateJson(socket: RecordingSocket): String =
        socket.sent.first { it.contains(""""type":"client/state"""") }

    @Test
    fun `client-state omits player object when player role is not active`() {
        val client = buildClient()
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("metadata@v1")),
        )
        val json = firstClientStateJson(socket)
        assertFalse("client/state should not carry a player object for an inactive role: $json", json.contains(""""player""""))
    }

    @Test
    fun `client-state includes player object when player role is active`() {
        val client = buildClient()
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1", "metadata@v1")),
        )
        val json = firstClientStateJson(socket)
        assertTrue("client/state should carry a player object for an active player role: $json", json.contains(""""player""""))
    }

    @Test
    fun `client-state includes player object when unversioned player role is active`() {
        val client = buildClient()
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player")),
        )
        val json = firstClientStateJson(socket)
        assertTrue("client/state should carry a player object for an active player role: $json", json.contains(""""player""""))
    }
}
