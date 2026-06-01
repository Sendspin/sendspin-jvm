package com.sendspin.protocol

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Outgoing messages (client → server) ──────────────────────────────────────

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    @Json(name = "manufacturer") val manufacturer: String? = null,
    @Json(name = "product_name") val productName: String? = null,
    @Json(name = "software_version") val softwareVersion: String? = null,
)

@JsonClass(generateAdapter = true)
data class ClientHelloPayload(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "name") val name: String,
    @Json(name = "device_info") val deviceInfo: DeviceInfo? = null,
    @Json(name = "version") val version: Int = 1,
    @Json(name = "supported_roles") val supportedRoles: List<String>,
    @Json(name = "player@v1_support") val playerSupport: PlayerSupport? = null,
    @Json(name = "metadata@v1_support") val metadataSupport: MetadataSupport? = null,
    @Json(name = "artwork@v1_support") val artworkSupport: ArtworkSupport? = null,
    @Json(name = "controller@v1_support") val controllerSupport: ControllerSupport? = null,
    @Json(name = "color@v1_support")      val colorSupport:      ColorSupport?      = null,
    @Json(name = "visualizer@v1_support") val visualizerSupport: VisualizerSupport? = null,
)

@JsonClass(generateAdapter = true)
data class ClientHello(
    @Json(name = "type") val type: String = "client/hello",
    @Json(name = "payload") val payload: ClientHelloPayload,
)

@JsonClass(generateAdapter = true)
data class PlayerSupport(
    @Json(name = "supported_formats") val supportedFormats: List<AudioFormat>,
    @Json(name = "buffer_capacity") val bufferCapacity: Int = 262144,
    @Json(name = "supported_commands") val supportedCommands: List<String> = listOf("volume", "mute"),
)

@JsonClass(generateAdapter = true)
data class AudioFormat(
    @Json(name = "codec") val codec: String,
    @Json(name = "channels") val channels: Int,
    @Json(name = "sample_rate") val sampleRate: Int,
    @Json(name = "bit_depth") val bitDepth: Int,
)

/** Empty capabilities object — metadata@v1 requires no additional fields. Serialises to {}. */
@JsonClass(generateAdapter = false)
class MetadataSupport

/** Empty capabilities object — controller@v1 requires no additional fields. Serialises to {}. */
@JsonClass(generateAdapter = false)
class ControllerSupport

/** Empty capabilities object — color@v1 requires no additional fields. Serialises to {}. */
@JsonClass(generateAdapter = false)
class ColorSupport

@JsonClass(generateAdapter = true)
data class VisualizerSupport(
    @Json(name = "types") val types: List<String>,
    @Json(name = "buffer_capacity") val bufferCapacity: Int,
    @Json(name = "rate_max") val rateMax: Int,
    @Json(name = "spectrum") val spectrum: VisualizerSpectrumConfig? = null,
)

@JsonClass(generateAdapter = true)
data class VisualizerSpectrumConfig(
    @Json(name = "n_disp_bins") val nDispBins: Int,
    @Json(name = "scale") val scale: String,
    @Json(name = "f_min") val fMin: Int,
    @Json(name = "f_max") val fMax: Int,
)

@JsonClass(generateAdapter = true)
data class ArtworkSupport(
    @Json(name = "channels") val channels: List<ArtworkChannel>,
)

@JsonClass(generateAdapter = true)
data class ArtworkChannel(
    @Json(name = "source") val source: String,
    @Json(name = "format") val format: String = "jpeg",
    @Json(name = "media_width") val mediaWidth: Int = 800,
    @Json(name = "media_height") val mediaHeight: Int = 800,
)

@JsonClass(generateAdapter = true)
data class PlayerStatePayload(
    @Json(name = "volume") val volume: Int? = null,
    @Json(name = "muted") val muted: Boolean? = null,
    @Json(name = "static_delay_ms") val staticDelayMs: Int = 0,
)

@JsonClass(generateAdapter = true)
data class ClientStateMsgPayload(
    @Json(name = "state") val state: String = "synchronized",
    @Json(name = "player") val player: PlayerStatePayload? = null,
)

@JsonClass(generateAdapter = true)
data class ClientStateMsg(
    @Json(name = "type") val type: String = "client/state",
    @Json(name = "payload") val payload: ClientStateMsgPayload,
)

@JsonClass(generateAdapter = true)
data class ClientTimePayload(
    @Json(name = "client_transmitted") val clientTime: Long,
)

@JsonClass(generateAdapter = true)
data class ClientTime(
    @Json(name = "type") val type: String = "client/time",
    @Json(name = "payload") val payload: ClientTimePayload,
)

@JsonClass(generateAdapter = true)
data class ClientGoodbyePayload(
    @Json(name = "reason") val reason: String = "user_request",
)

@JsonClass(generateAdapter = true)
data class ClientGoodbye(
    @Json(name = "type") val type: String = "client/goodbye",
    @Json(name = "payload") val payload: ClientGoodbyePayload,
)

@JsonClass(generateAdapter = true)
data class ClientCommandControllerPayload(
    @Json(name = "command") val command: String,
    @Json(name = "volume") val volume: Int? = null,
    @Json(name = "muted") val muted: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class ClientCommandPayload(
    @Json(name = "controller") val controller: ClientCommandControllerPayload? = null,
)

@JsonClass(generateAdapter = true)
data class ClientCommand(
    @Json(name = "type") val type: String = "client/command",
    @Json(name = "payload") val payload: ClientCommandPayload,
)

// ── Incoming messages (server → client) ──────────────────────────────────────

/** Discriminated union for all server → client JSON messages. */
sealed interface IncomingMessage

@JsonClass(generateAdapter = true)
data class ServerHello(
    @Json(name = "server_id") val serverId: String,
    @Json(name = "name") val name: String,
    @Json(name = "version") val version: Int,
    @Json(name = "active_roles") val activeRoles: List<String>,
    @Json(name = "connection_reason") val connectionReason: String? = null,
) : IncomingMessage

@JsonClass(generateAdapter = true)
data class ServerState(
    @Json(name = "metadata")   val metadata:   TrackMetadataMsg? = null,
    @Json(name = "controller") val controller: ControllerState?  = null,
    @Json(name = "color")      val color:      ColorState?       = null,
) : IncomingMessage

@JsonClass(generateAdapter = true)
data class TrackMetadataMsg(
    @Json(name = "timestamp") val timestamp: Long = 0,
    @Json(name = "title") val title: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "artist") val artist: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "album_artist") val albumArtist: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "album") val album: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "artwork_url") val artworkUrl: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "year") val year: JsonOptional<Int> = JsonOptional.Absent,
    @Json(name = "track") val track: JsonOptional<Int> = JsonOptional.Absent,
    @Json(name = "progress") val progress: ProgressInfo? = null,
    @Deprecated("Sent by old servers only; use ControllerState.repeat instead")
    @Json(name = "repeat") val repeat: JsonOptional<String> = JsonOptional.Absent,
    @Deprecated("Sent by old servers only; use ControllerState.shuffle instead")
    @Json(name = "shuffle") val shuffle: JsonOptional<Boolean> = JsonOptional.Absent,
)

@JsonClass(generateAdapter = true)
data class ProgressInfo(
    @Json(name = "track_progress") val trackProgress: Long = 0,   // ms
    @Json(name = "track_duration") val trackDuration: Long = 0,   // ms, 0 = unknown
    @Json(name = "playback_speed") val playbackSpeed: Int = 1000, // 1000 = 1×
)

@JsonClass(generateAdapter = true)
data class ControllerState(
    @Json(name = "supported_commands") val supportedCommands: List<String> = emptyList(),
    @Json(name = "volume") val volume: Int = 100,
    @Json(name = "muted") val muted: Boolean = false,
    @Json(name = "repeat") val repeat: JsonOptional<String> = JsonOptional.Absent,
    @Json(name = "shuffle") val shuffle: JsonOptional<Boolean> = JsonOptional.Absent,
)

@JsonClass(generateAdapter = true)
data class ColorState(
    @Json(name = "timestamp")        val timestamp:       Long       = 0,
    @Json(name = "background_dark")  val backgroundDark:  List<Int>? = null,
    @Json(name = "background_light") val backgroundLight: List<Int>? = null,
    @Json(name = "primary")          val primary:         List<Int>? = null,
    @Json(name = "accent")           val accent:          List<Int>? = null,
    @Json(name = "on_dark")          val onDark:          List<Int>? = null,
    @Json(name = "on_light")         val onLight:         List<Int>? = null,
)

@JsonClass(generateAdapter = true)
data class ServerTime(
    @Json(name = "client_transmitted") val clientTime: Long,  // t1: client send time
    @Json(name = "server_received") val serverReceive: Long,  // t2
    @Json(name = "server_transmitted") val serverSend: Long,  // t3
    // t4 is captured locally when the response arrives — not sent by server
) : IncomingMessage

@JsonClass(generateAdapter = true)
data class StreamVisualizerConfig(
    @Json(name = "types") val types: List<String>,
    @Json(name = "rate_max") val rateMax: Int,
    @Json(name = "tracks_downbeats") val tracksDownbeats: Boolean = false,
    @Json(name = "spectrum") val spectrum: VisualizerSpectrumConfig? = null,
)

@JsonClass(generateAdapter = true)
data class StreamStart(
    /** Present when an audio stream is active; null when only artwork is being configured. */
    @Json(name = "player") val player: StreamFormat? = null,
    @Json(name = "artwork") val artwork: StreamArtworkConfig? = null,
    @Json(name = "visualizer") val visualizer: StreamVisualizerConfig? = null,
) : IncomingMessage

@JsonClass(generateAdapter = true)
data class StreamArtworkConfig(
    @Json(name = "channels") val channels: List<StreamArtworkChannel>,
)

/** Artwork channel descriptor as sent by the server in stream/start (uses width/height, not media_width/media_height). */
@JsonClass(generateAdapter = true)
data class StreamArtworkChannel(
    @Json(name = "source") val source: String,
    @Json(name = "format") val format: String,
    @Json(name = "width") val width: Int,
    @Json(name = "height") val height: Int,
)

@JsonClass(generateAdapter = true)
data class StreamFormat(
    @Json(name = "codec") val codec: String,
    @Json(name = "sample_rate") val sampleRate: Int,
    @Json(name = "channels") val channels: Int,
    @Json(name = "bit_depth") val bitDepth: Int,
    @Json(name = "codec_header") val codecHeader: String? = null,
)

/** Server cleared the buffer (e.g. on seek). */
object StreamClear : IncomingMessage

/** Server stopped one or more role streams. Null [roles] means all active streams. */
@JsonClass(generateAdapter = true)
data class StreamEnd(
    @Json(name = "roles") val roles: List<String>? = null,
) : IncomingMessage

enum class GroupPlaybackState { PLAYING, PAUSED, STOPPED }

@JsonClass(generateAdapter = true)
data class GroupUpdate(
    @Json(name = "playback_state") val playbackState: String? = null,
    @Json(name = "volume") val volume: Int? = null,
    @Json(name = "muted") val muted: Boolean? = null,
) : IncomingMessage {
    val typedPlaybackState: GroupPlaybackState? get() = when (playbackState) {
        "playing" -> GroupPlaybackState.PLAYING
        "paused"  -> GroupPlaybackState.PAUSED
        "stopped" -> GroupPlaybackState.STOPPED
        else      -> null
    }
}

/** Unrecognised / unhandled message type — ignored gracefully. */
data class UnknownMessage(val type: String) : IncomingMessage

// ── Binary message types ──────────────────────────────────────────────────────

const val BINARY_TYPE_AUDIO: Byte = 0x04
const val BINARY_TYPE_ARTWORK_0: Byte = 0x08
const val BINARY_TYPE_ARTWORK_1: Byte = 0x09
const val BINARY_TYPE_ARTWORK_2: Byte = 0x0A
const val BINARY_TYPE_ARTWORK_3: Byte = 0x0B
const val BINARY_TYPE_VISUALIZER_LOUDNESS: Byte = 0x10
const val BINARY_TYPE_VISUALIZER_BEAT:     Byte = 0x11
const val BINARY_TYPE_VISUALIZER_F_PEAK:   Byte = 0x12
const val BINARY_TYPE_VISUALIZER_SPECTRUM: Byte = 0x13
const val BINARY_TYPE_VISUALIZER_PEAK:     Byte = 0x14
const val BINARY_TYPE_VISUALIZER_PITCH:    Byte = 0x15

/** A single visualizer analysis frame from the server, carrying a server-clock timestamp. */
sealed interface VisualizerFrame {
    val serverTimestampMicros: Long

    /** Overall A-weighted loudness. [value] is 0–65535 (−60 dB → 0, 0 dB → 65535). */
    data class Loudness(override val serverTimestampMicros: Long, val value: Int) : VisualizerFrame
    /** Musical beat event. [isDownbeat] is true on bar starts when the server tracks downbeats. */
    data class Beat(override val serverTimestampMicros: Long, val isDownbeat: Boolean) : VisualizerFrame
    /** Dominant FFT bin. [freqHz] 0 = no peak; [amplitude] 0–65535. */
    data class FPeak(override val serverTimestampMicros: Long, val freqHz: Int, val amplitude: Int) : VisualizerFrame
    /** Per-display-bin magnitudes, 0–65535 each, low to high frequency. */
    data class Spectrum(override val serverTimestampMicros: Long, val bins: ShortArray) : VisualizerFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Spectrum) return false
            return serverTimestampMicros == other.serverTimestampMicros && bins.contentEquals(other.bins)
        }
        override fun hashCode(): Int = 31 * serverTimestampMicros.hashCode() + bins.contentHashCode()
    }
    /** Energy onset event (any transient). [strength] 0–255. */
    data class Peak(override val serverTimestampMicros: Long, val strength: Int) : VisualizerFrame
    /** Perceived pitch. [midiFixed88] is an 8.8 fixed-point MIDI note; [confidence] 0–255. */
    data class Pitch(override val serverTimestampMicros: Long, val midiFixed88: Int, val confidence: Int) : VisualizerFrame
}

/** A decoded audio chunk ready for scheduling. */
data class AudioChunk(
    /** Server-clock timestamp in microseconds at which this chunk should begin playing. */
    val serverTimestampMicros: Long,
    /** Raw encoded audio bytes (codec determined by the most recent [StreamStart]). */
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return serverTimestampMicros == other.serverTimestampMicros
    }
    override fun hashCode(): Int = serverTimestampMicros.hashCode()
}
