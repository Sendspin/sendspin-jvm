package com.sendspin.protocol

import com.squareup.moshi.Moshi
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
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
    data class BinaryVisualizer(val frame: VisualizerFrame) : BinaryMessage

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
            BINARY_TYPE_VISUALIZER_LOUDNESS -> parseVisualizerLoudness(timestamp, payload)
            BINARY_TYPE_VISUALIZER_BEAT     -> parseVisualizerBeat(timestamp, payload)
            BINARY_TYPE_VISUALIZER_F_PEAK   -> parseVisualizerFPeak(timestamp, payload)
            BINARY_TYPE_VISUALIZER_SPECTRUM -> parseVisualizerSpectrum(timestamp, payload)
            BINARY_TYPE_VISUALIZER_PEAK     -> parseVisualizerPeak(timestamp, payload)
            BINARY_TYPE_VISUALIZER_PITCH    -> parseVisualizerPitch(timestamp, payload)
            else -> {
                Timber.v("MessageParser: unknown binary type 0x%02x", msgType)
                null
            }
        }
    }

    private fun parseVisualizerLoudness(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 2) return malformed("loudness", payload.size, 2)
        val value = ByteBuffer.wrap(payload).short.toInt() and 0xFFFF
        return BinaryVisualizer(VisualizerFrame.Loudness(ts, value))
    }

    private fun parseVisualizerBeat(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.isEmpty()) return malformed("beat", 0, 1)
        return BinaryVisualizer(VisualizerFrame.Beat(ts, isDownbeat = (payload[0].toInt() and 0x01) != 0))
    }

    private fun parseVisualizerFPeak(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 4) return malformed("f_peak", payload.size, 4)
        val buf = ByteBuffer.wrap(payload)
        val freq = buf.short.toInt() and 0xFFFF
        val amp  = buf.short.toInt() and 0xFFFF
        return BinaryVisualizer(VisualizerFrame.FPeak(ts, freq, amp))
    }

    private fun parseVisualizerSpectrum(ts: Long, payload: ByteArray): BinaryMessage? {
        val n = payload.size / 2
        val buf = ByteBuffer.wrap(payload)
        val bins = ShortArray(n) { buf.short }
        return BinaryVisualizer(VisualizerFrame.Spectrum(ts, bins))
    }

    private fun parseVisualizerPeak(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.isEmpty()) return malformed("peak", 0, 1)
        return BinaryVisualizer(VisualizerFrame.Peak(ts, strength = payload[0].toInt() and 0xFF))
    }

    private fun parseVisualizerPitch(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 3) return malformed("pitch", payload.size, 3)
        val buf = ByteBuffer.wrap(payload)
        val midi       = buf.short.toInt() and 0xFFFF
        val confidence = buf.get().toInt() and 0xFF
        return BinaryVisualizer(VisualizerFrame.Pitch(ts, midi, confidence))
    }

    private fun malformed(type: String, actual: Int, required: Int): BinaryMessage? {
        Timber.w("MessageParser: visualizer/%s too short (%d bytes, need %d)", type, actual, required)
        return null
    }
}
