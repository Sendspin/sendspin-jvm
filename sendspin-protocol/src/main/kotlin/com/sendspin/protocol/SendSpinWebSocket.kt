package com.sendspin.protocol

/** Thin abstraction over OkHttp (client-initiated) and Java-WebSocket (server-initiated) sockets. */
interface SendSpinWebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?)
}

class OkHttpWebSocketAdapter(private val ws: okhttp3.WebSocket) : SendSpinWebSocket {
    override fun send(text: String) = ws.send(text)
    override fun close(code: Int, reason: String?) { ws.close(code, reason) }
}

class JavaWebSocketAdapter(private val ws: org.java_websocket.WebSocket) : SendSpinWebSocket {
    override fun send(text: String): Boolean = try {
        ws.send(text)
        true
    } catch (_: Exception) {
        false
    }
    override fun close(code: Int, reason: String?) { ws.close(code, reason ?: "") }
}
