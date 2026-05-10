package com.sendspin.protocol

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import com.sendspin.protocol.ProtocolLog as Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Runs a WebSocket server that accepts incoming connections from SendSpin servers.
 *
 * For each new connection the host:
 *  1. Sends `client/hello` to begin the handshake.
 *  2. Waits for `server/hello` to learn the server's `connection_reason` and `server_id`.
 *  3. If a connection is already active, applies the multi-server decision rules from the spec:
 *     - prefer the connection with `connection_reason: "playback"` over `"discovery"`
 *     - when both have `"discovery"`, prefer the last-played server (by `server_id`)
 *     - send `client/goodbye` with reason `"another_server"` to the rejected socket
 *  4. Calls [SendSpinClient.acceptIncomingConnection] for the accepted socket.
 *
 * Only one pending (pre-hello) connection is accepted at a time; a second one arriving while
 * a handshake is in progress is immediately rejected with `"another_server"`.
 */
class SendSpinServerHost(
    private val client: SendSpinClient,
    private val moshi: Moshi,
    /** Returns the `server_id` of the last server that had `playback_state: "playing"`. */
    private val getLastPlayedServerId: () -> String = { "" },
    port: Int = SERVER_PORT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pendingHelloTimeoutMs: Long = PENDING_HELLO_TIMEOUT_MS,
) : WebSocketServer(InetSocketAddress(port)) {

    private val parser = MessageParser(moshi)

    /** Completes once the current start attempt has bound the socket. Reset on each [startServer] call. */
    @Volatile var serverReady = CompletableDeferred<Unit>()
        private set

    @Volatile private var started = false

    init {
        setReuseAddr(true)
    }

    // Active connection (messages routed to SendSpinClient)
    @Volatile private var activeConn: WebSocket? = null
    @Volatile private var activeReason: String? = null
    @Volatile private var activeServerId: String? = null

    // Pending connection (waiting for server/hello before we decide)
    @Volatile private var pendingConn: WebSocket? = null
    @Volatile private var pendingConnTimeoutJob: Job? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Timber.i("SendSpinServerHost: incoming connection from %s", conn.remoteSocketAddress)
        if (pendingConn != null) {
            Timber.w("SendSpinServerHost: rejecting connection (handshake already in progress)")
            sendGoodbye(conn, "another_server")
            conn.close(1000, "another_server")
            return
        }
        pendingConn = conn
        pendingConnTimeoutJob = scope.launch {
            delay(pendingHelloTimeoutMs)
            if (pendingConn == conn) {
                Timber.w("SendSpinServerHost: pending connection timed out, closing")
                sendGoodbye(conn, "another_server")
                conn.close(1000, "timeout")
                pendingConn = null
            }
        }
        try {
            val helloJson = client.buildClientHelloJson()
            Timber.d("SendSpinServerHost: >> %s", helloJson)
            conn.send(helloJson)
        } catch (e: Exception) {
            Timber.w(e, "SendSpinServerHost: failed to send client/hello, dropping pending connection")
            pendingConnTimeoutJob?.cancel()
            pendingConnTimeoutJob = null
            pendingConn = null
            conn.close(1000, "error")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Timber.d("SendSpinServerHost: << %s", message)
        when {
            conn == pendingConn -> {
                val msg = parser.parseText(message)
                if (msg is ServerHello) {
                    resolveConnection(conn, msg)
                } else {
                    Timber.w("SendSpinServerHost: pending connection sent non-hello message, closing")
                    sendGoodbye(conn, "another_server")
                    conn.close(1000, "another_server")
                    pendingConn = null
                }
            }
            conn == activeConn -> client.handleTextMessage(message)
            else -> Timber.w("SendSpinServerHost: message from unknown connection, ignoring")
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        if (conn == activeConn) {
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            client.handleBinaryMessage(ByteString.of(*bytes))
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Timber.i("SendSpinServerHost: connection closed (%d %s)", code, reason)
        when (conn) {
            activeConn -> {
                activeConn = null
                activeReason = null
                activeServerId = null
                client.onServerSocketClosed(code, reason)
            }
            pendingConn -> {
                pendingConnTimeoutJob?.cancel()
                pendingConnTimeoutJob = null
                pendingConn = null
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Timber.e(ex, "SendSpinServerHost: socket error (conn=%s)", conn?.remoteSocketAddress)
        if (conn == null) {
            serverReady.completeExceptionally(ex)
            started = false
            pendingConnTimeoutJob?.cancel()
            pendingConnTimeoutJob = null
            pendingConn = null
        }
        if (conn == null || conn == activeConn) {
            activeConn = null
            activeReason = null
            activeServerId = null
            client.onServerSocketError(ex)
        } else if (conn == pendingConn) {
            pendingConnTimeoutJob?.cancel()
            pendingConnTimeoutJob = null
            pendingConn = null
        }
    }

    override fun onStart() {
        Timber.i("SendSpinServerHost: listening on port %d", port)
        serverReady.complete(Unit)
    }

    fun startServer() {
        if (started) return
        started = true
        serverReady = CompletableDeferred()
        start()
    }

    fun stopServer() {
        started = false
        pendingConnTimeoutJob?.cancel()
        pendingConnTimeoutJob = null
        try {
            stop(0)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            Timber.e(ex, "SendSpinServerHost: interrupted while stopping")
        } catch (ex: Exception) {
            Timber.e(ex, "SendSpinServerHost: error while stopping")
        }
    }

    private fun resolveConnection(newConn: WebSocket, newHello: ServerHello) {
        pendingConnTimeoutJob?.cancel()
        pendingConnTimeoutJob = null
        val current = activeConn
        if (current == null) {
            activate(newConn, newHello)
            return
        }

        val newReason = newHello.connectionReason
        val acceptNew = when {
            newReason == "playback" && activeReason != "playback" -> true
            activeReason == "playback" && newReason != "playback" -> false
            // Both discovery (or both playback, or null): prefer last-played server
            newHello.serverId == getLastPlayedServerId() -> true
            else -> false
        }

        if (acceptNew) {
            Timber.i("SendSpinServerHost: accepting new server '%s' (reason=%s), rejecting current '%s'",
                newHello.name, newReason, activeServerId)
            sendGoodbye(current, "another_server")
            current.close(1000, "another_server")
            activate(newConn, newHello)
        } else {
            Timber.i("SendSpinServerHost: keeping current server '%s', rejecting new '%s' (reason=%s)",
                activeServerId, newHello.name, newReason)
            sendGoodbye(newConn, "another_server")
            newConn.close(1000, "another_server")
            pendingConn = null
        }
    }

    private fun activate(conn: WebSocket, hello: ServerHello) {
        activeConn = conn
        activeReason = hello.connectionReason
        activeServerId = hello.serverId
        pendingConn = null
        Timber.i("SendSpinServerHost: activating server '%s' (id=%s reason=%s)",
            hello.name, hello.serverId, hello.connectionReason)
        client.acceptIncomingConnection(JavaWebSocketAdapter(conn), hello)
    }

    private fun sendGoodbye(conn: WebSocket, reason: String) {
        try {
            conn.send(client.buildClientGoodbyeJson(reason))
        } catch (e: Exception) {
            Timber.w(e, "SendSpinServerHost: failed to send client/goodbye")
        }
    }

    companion object {
        const val SERVER_PORT = 8928
        private const val PENDING_HELLO_TIMEOUT_MS = 5_000L
    }
}
