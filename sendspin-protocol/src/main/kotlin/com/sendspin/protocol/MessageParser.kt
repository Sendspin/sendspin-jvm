package com.sendspin.protocol

import com.squareup.moshi.Moshi
import okio.ByteString
import org.json.JSONObject
import com.sendspin.protocol.ProtocolLog as Timber

/**
 * Parses raw WebSocket frames into typed [IncomingMessage] or [AudioChunk].
 *
 * Text frames carry JSON-encoded control/metadata messages; binary frames carry
 * time-stamped audio or artwork payloads.
 *
 * Binary audio frame layout:
 * ```
 * [0]      1 byte  – message type (0x04 = audio, 0x08-0x0B = artwork channels 0-3)
 * [1..8]   8 bytes – big-endian int64 server timestamp in microseconds
 * [9..]    N bytes – codec-encoded audio data
 * ```
 */
class MessageParser(moshi: Moshi) {

    private val serverHelloAdapter = moshi.adapter(ServerHello::class.java)
    private val serverStateAdapter = moshi.adapter(ServerState::class.java)
    private val serverTimeAdapter  = moshi.adapter(ServerTime::class.java)
    private val streamStartAdapter = moshi.adapter(StreamStart::class.java)
    private val streamEndAdapter   = moshi.adapter(StreamEnd::class.java)
    private val groupUpdateAdapter = moshi.adapter(GroupUpdate::class.java)

    // ── Text (JSON) ───────────────────────────────────────────────────────────

    fun parseText(json: String): IncomingMessage? {
        return try {
            val root = JSONObject(json)
            val type = root.optString("type", "")
            // Protocol wraps message-specific fields in a "payload" object.
            val body = if (root.has("payload")) root.getJSONObject("payload").toString() else json
            when (type) {
                "server/hello"   -> serverHelloAdapter.fromJson(body)
                "server/state"   -> serverStateAdapter.fromJson(body)
                "server/time"    -> serverTimeAdapter.fromJson(body)
                "stream/start"   -> streamStartAdapter.fromJson(body)
                "stream/clear"   -> StreamClear
                "stream/end"     -> streamEndAdapter.fromJson(body) ?: StreamEnd()
                "group/update"   -> groupUpdateAdapter.fromJson(body)
                else             -> UnknownMessage(type).also {
                    Timber.v("MessageParser: unknown message type '%s'", type)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MessageParser: failed to parse text frame")
            null
        }
    }

    // ── Binary ────────────────────────────────────────────────────────────────

    sealed interface BinaryMessage
    data class BinaryAudio(val chunk: AudioChunk) : BinaryMessage
    data class BinaryArtwork(val channel: Int, val serverTimestampMicros: Long, val data: ByteArray) : BinaryMessage

    fun parseBinary(bytes: ByteString): BinaryMessage? {
        if (bytes.size < 9) {
            Timber.w("MessageParser: binary frame too short (%d bytes)", bytes.size)
            return null
        }
        val msgType = bytes[0]
        val timestamp = bytes.substring(1, 9).asByteBuffer().getLong()
        val payload   = bytes.substring(9).toByteArray()

        return when (msgType) {
            BINARY_TYPE_AUDIO -> BinaryAudio(AudioChunk(timestamp, payload))
            BINARY_TYPE_ARTWORK_0 -> BinaryArtwork(0, timestamp, payload)
            BINARY_TYPE_ARTWORK_1 -> BinaryArtwork(1, timestamp, payload)
            BINARY_TYPE_ARTWORK_2 -> BinaryArtwork(2, timestamp, payload)
            BINARY_TYPE_ARTWORK_3 -> BinaryArtwork(3, timestamp, payload)
            else -> {
                Timber.v("MessageParser: unknown binary type 0x%02x", msgType)
                null
            }
        }
    }
}
