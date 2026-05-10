package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.junit.Before
import org.junit.Test

class SendSpinServerHostTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private lateinit var client: SendSpinClient
    private lateinit var host: SendSpinServerHost
    private val handshake = mockk<ClientHandshake>(relaxed = true)

    private var lastPlayedServerId = ""

    @Before
    fun setUp() {
        lastPlayedServerId = ""
        client = mockk(relaxed = true)
        every { client.buildClientHelloJson() } returns """{"type":"client/hello","payload":{}}"""
        every { client.buildClientGoodbyeJson(any()) } returns """{"type":"client/goodbye","payload":{}}"""
        host = SendSpinServerHost(
            client = client,
            moshi = moshi,
            getLastPlayedServerId = { lastPlayedServerId },
            port = 0,
            scope = CoroutineScope(SupervisorJob()),
            pendingHelloTimeoutMs = Long.MAX_VALUE,
        )
    }

    private fun mockConn(): WebSocket = mockk(relaxed = true)

    private fun serverHelloJson(serverId: String, connectionReason: String?) =
        """{"type":"server/hello","payload":{"server_id":"$serverId","name":"Server $serverId","version":1,"active_roles":["player@v1"]${if (connectionReason != null) ""","connection_reason":"$connectionReason"""" else ""}}}"""

    /** Simulates a connection that completes the handshake and becomes active. */
    private fun connectAndActivate(conn: WebSocket, serverId: String, connectionReason: String?) {
        host.onOpen(conn, handshake)
        host.onMessage(conn, serverHelloJson(serverId, connectionReason))
    }

    @Test
    fun `new playback connection replaces active discovery connection`() {
        val discoveryConn = mockConn()
        val playbackConn = mockConn()

        connectAndActivate(discoveryConn, "server-discovery", "discovery")

        host.onOpen(playbackConn, handshake)
        host.onMessage(playbackConn, serverHelloJson("server-playback", "playback"))

        verify { discoveryConn.close(1000, "another_server") }
        verify(exactly = 0) { playbackConn.close(any(), any<String>()) }
    }

    @Test
    fun `active playback connection keeps out new discovery connection`() {
        val playbackConn = mockConn()
        val discoveryConn = mockConn()

        connectAndActivate(playbackConn, "server-playback", "playback")

        host.onOpen(discoveryConn, handshake)
        host.onMessage(discoveryConn, serverHelloJson("server-discovery", "discovery"))

        verify { discoveryConn.close(1000, "another_server") }
        verify(exactly = 0) { playbackConn.close(any(), any<String>()) }
    }

    @Test
    fun `both discovery, last-played server wins`() {
        val currentConn = mockConn()
        val newConn = mockConn()
        lastPlayedServerId = "server-b"

        connectAndActivate(currentConn, "server-a", "discovery")

        host.onOpen(newConn, handshake)
        host.onMessage(newConn, serverHelloJson("server-b", "discovery"))

        verify { currentConn.close(1000, "another_server") }
        verify(exactly = 0) { newConn.close(any(), any<String>()) }
    }

    @Test
    fun `both discovery, neither is last-played, keep current`() {
        val currentConn = mockConn()
        val newConn = mockConn()
        lastPlayedServerId = "server-c"

        connectAndActivate(currentConn, "server-a", "discovery")

        host.onOpen(newConn, handshake)
        host.onMessage(newConn, serverHelloJson("server-b", "discovery"))

        verify { newConn.close(1000, "another_server") }
        verify(exactly = 0) { currentConn.close(any(), any<String>()) }
    }
}
