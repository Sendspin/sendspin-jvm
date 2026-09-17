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
 * time-stamped audio, artwork, or visualizer payloads.
 *
 * Binary audio frame layout:
 * ```
 * [0]      1 byte  – message type (0x04 = audio)
 * [1..8]   8 bytes – big-endian int64 server timestamp in microseconds
 * [9..]    N bytes – codec-encoded audio data
 * ```
 *
 * Binary artwork frames (message types 0x08-0x0B = channels 0-3) use the announce/part/cancel
 * chunking scheme of spec PR #188 — see [parseArtwork].
 */
class MessageParser(moshi: Moshi) {

    private val serverHelloAdapter = moshi.adapter(ServerHello::class.java)
    private val serverStateAdapter = moshi.adapter(ServerState::class.java)
    private val serverTimeAdapter  = moshi.adapter(ServerTime::class.java)
    private val streamStartAdapter = moshi.adapter(StreamStart::class.java)
    private val streamClearAdapter = moshi.adapter(StreamClear::class.java)
    private val streamEndAdapter   = moshi.adapter(StreamEnd::class.java)
    private val groupUpdateAdapter    = moshi.adapter(GroupUpdate::class.java)
    private val serverCommandAdapter  = moshi.adapter(ServerCommand::class.java)

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
                "stream/clear"   -> streamClearAdapter.fromJson(body) ?: StreamClear()
                "stream/end"     -> streamEndAdapter.fromJson(body) ?: StreamEnd()
                "group/update"   -> groupUpdateAdapter.fromJson(body)
                "server/command" -> serverCommandAdapter.fromJson(body)
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
    data class BinaryArtwork(val message: ArtworkBinaryMessage) : BinaryMessage
    data class BinaryVisualizer(val frame: VisualizerFrame) : BinaryMessage

    /** A malformed message or sequence that the spec requires closing the connection over. */
    data class Malformed(val reason: String) : BinaryMessage

    fun parseBinary(bytes: ByteString): BinaryMessage? {
        if (bytes.size == 0) {
            Timber.w("MessageParser: empty binary frame")
            return null
        }
        val msgType = bytes[0]

        if (msgType.toInt() in BINARY_TYPE_ARTWORK_0.toInt()..BINARY_TYPE_ARTWORK_3.toInt()) {
            return parseArtwork(msgType, bytes)
        }

        if (bytes.size < 9) {
            Timber.w("MessageParser: binary frame too short (%d bytes)", bytes.size)
            return null
        }
        val timestamp = bytes.substring(1, 9).asByteBuffer().getLong()
        val payload   = bytes.substring(9).toByteArray()

        return when (msgType) {
            BINARY_TYPE_AUDIO -> BinaryAudio(AudioChunk(timestamp, payload))
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

    /**
     * Parses one artwork announce/part/cancel message (spec PR #188).
     *
     * ```
     * Announce: [type][flags][timestamp:8][total_size:4]  (14 bytes)
     * Part:     [type][flags][data...]
     * Cancel:   [type][flags]                             (2 bytes)
     * ```
     *
     * Message-format errors (short/oversized frame, wrong announce/cancel length, a reserved
     * flag bit set, or both cancel and announce bits set) are protocol errors per spec: the
     * caller MUST close the connection on [Malformed].
     */
    private fun parseArtwork(msgType: Byte, bytes: ByteString): BinaryMessage {
        val channel = (msgType - BINARY_TYPE_ARTWORK_0)
        if (bytes.size < 2) return Malformed("artwork message under 2 bytes")
        if (bytes.size > ARTWORK_MAX_MESSAGE_BYTES) return Malformed("artwork message exceeds ${ARTWORK_MAX_MESSAGE_BYTES} bytes")

        val flags = bytes[1].toInt() and 0xFF
        if (flags and ARTWORK_FLAG_RESERVED_MASK != 0) return Malformed("artwork message has a nonzero reserved flag bit")
        val isCancel = flags and ARTWORK_FLAG_CANCEL != 0
        val isAnnounce = flags and ARTWORK_FLAG_ANNOUNCE != 0
        if (isCancel && isAnnounce) return Malformed("artwork message sets both cancel and announce flags")

        return when {
            isCancel -> {
                if (bytes.size != 2) return Malformed("artwork cancel message longer than 2 bytes")
                BinaryArtwork(ArtworkCancel(channel))
            }
            isAnnounce -> {
                if (bytes.size != 14) return Malformed("artwork announce message length ${bytes.size} != 14")
                val timestamp = bytes.substring(2, 10).asByteBuffer().getLong()
                val totalSize = bytes.substring(10, 14).asByteBuffer().getInt().toLong() and 0xFFFFFFFFL
                BinaryArtwork(ArtworkAnnounce(channel, timestamp, totalSize))
            }
            else -> BinaryArtwork(ArtworkPart(channel, bytes.substring(2).toByteArray()))
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
