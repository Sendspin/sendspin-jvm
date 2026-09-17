package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec PR #209: `client/leave` replaces the old `available: false` immediately followed by
 * `available: true` "flap" for a client that wants to stop taking part in its group's playback.
 */
class ClientLeaveTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val preferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
        supportedOptionalRoles = setOf(OptionalRole.CONTROLLER),
    )

    private class CapturingSocket : SendSpinWebSocket {
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
        preferences = preferences,
        clientId = "test-id",
        clientName = "Test",
        manufacturer = "Acme",
        productName = "TestDevice",
        softwareVersion = "1",
        audioPlayerFactory = null,
    )

    @Test
    fun `leave sends client-leave with an empty payload`() {
        val client = buildClient()
        val socket = CapturingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("controller@v1")),
        )

        client.leave()

        val leaveJson = socket.sent.firstOrNull { it.contains(""""type":"client/leave"""") }
        assertTrue("no client/leave message was sent", leaveJson != null)
        assertTrue(leaveJson!!.contains(""""payload":{}"""))
    }

    @Test
    fun `leave is a no-op when disconnected`() {
        // Should not throw even with no socket attached.
        buildClient().leave()
    }
}
