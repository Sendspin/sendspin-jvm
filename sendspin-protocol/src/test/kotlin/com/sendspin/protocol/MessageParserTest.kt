package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class MessageParserTest {

    private lateinit var parser: MessageParser

    @Before
    fun setUp() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        parser = MessageParser(moshi)
    }

    // ── Text messages ─────────────────────────────────────────────────────────

    @Test
    fun `parse server hello`() {
        val json = """
            {
              "type": "server/hello",
              "payload": {
                "server_id": "abc123",
                "name": "My Server",
                "version": 1,
                "active_roles": ["player@v1", "metadata@v1"]
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json)
        assertTrue(msg is ServerHello)
        val hello = msg as ServerHello
        assertEquals("My Server", hello.name)
        assertEquals(listOf("player@v1", "metadata@v1"), hello.activeRoles)
    }

    @Test
    fun `parse server state with metadata`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "metadata": {
                  "title": "Test Track",
                  "artist": "Test Artist",
                  "album": "Test Album",
                  "artwork_url": "http://example.com/art.jpg",
                  "progress": {
                    "track_progress": 30000,
                    "track_duration": 240000,
                    "playback_speed": 1000
                  }
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Present("Test Track"), msg.metadata?.title)
        assertEquals(JsonOptional.Present("Test Artist"), msg.metadata?.artist)
        assertEquals(30000L, msg.metadata?.progress?.trackProgress)
        assertEquals(240000L, msg.metadata?.progress?.trackDuration)
    }

    @Test
    fun `parse server state - absent field is JsonOptional Absent`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "metadata": {
                  "title": "Radio Station"
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Present("Radio Station"), msg.metadata?.title)
        assertEquals(JsonOptional.Absent, msg.metadata?.artist)
        assertEquals(JsonOptional.Absent, msg.metadata?.album)
    }

    @Test
    fun `parse server state - explicit null field is JsonOptional Present null`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "metadata": {
                  "title": "Radio Station",
                  "artist": null,
                  "album": null
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Present("Radio Station"), msg.metadata?.title)
        assertEquals(JsonOptional.Present(null), msg.metadata?.artist)
        assertEquals(JsonOptional.Present(null), msg.metadata?.album)
    }

    @Test
    fun `parse stream start`() {
        val json = """
            {
              "type": "stream/start",
              "payload": {
                "player": {
                  "codec": "opus",
                  "sample_rate": 48000,
                  "channels": 2,
                  "bit_depth": 16
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as StreamStart
        assertNotNull(msg.player)
        assertEquals("opus", msg.player!!.codec)
        assertEquals(48000, msg.player!!.sampleRate)
        assertEquals(2, msg.player!!.channels)
    }

    @Test
    fun `parse stream clear`() {
        val msg = parser.parseText("""{"type":"stream/clear"}""")
        assertTrue(msg is StreamClear)
    }

    @Test
    fun `parse stream end no roles`() {
        val msg = parser.parseText("""{"type":"stream/end"}""") as StreamEnd
        assertNull(msg.roles)
    }

    @Test
    fun `parse stream end with roles`() {
        val msg = parser.parseText("""{"type":"stream/end","payload":{"roles":["player"]}}""") as StreamEnd
        assertEquals(listOf("player"), msg.roles)
    }

    @Test
    fun `parse server time`() {
        val json = """
            {
              "type": "server/time",
              "payload": {
                "client_transmitted": 1000000,
                "server_received": 1010000,
                "server_transmitted": 1010100
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerTime
        assertEquals(1_000_000L, msg.clientTime)
        assertEquals(1_010_000L, msg.serverReceive)
        assertEquals(1_010_100L, msg.serverSend)
    }

    @Test
    fun `parse group update playing`() {
        val json = """{"type":"group/update","payload":{"playback_state":"playing"}}"""
        val msg = parser.parseText(json) as GroupUpdate
        assertEquals("playing", msg.playbackState)
        assertEquals(GroupPlaybackState.PLAYING, msg.typedPlaybackState)
    }

    @Test
    fun `parse group update paused`() {
        val json = """{"type":"group/update","payload":{"playback_state":"paused"}}"""
        val msg = parser.parseText(json) as GroupUpdate
        assertEquals(GroupPlaybackState.PAUSED, msg.typedPlaybackState)
    }

    @Test
    fun `parse group update stopped`() {
        val json = """{"type":"group/update","payload":{"playback_state":"stopped"}}"""
        val msg = parser.parseText(json) as GroupUpdate
        assertEquals(GroupPlaybackState.STOPPED, msg.typedPlaybackState)
    }

    @Test
    fun `parse group update unknown playback state returns null typed`() {
        val json = """{"type":"group/update","payload":{"playback_state":"rewinding"}}"""
        val msg = parser.parseText(json) as GroupUpdate
        assertEquals("rewinding", msg.playbackState)
        assertNull(msg.typedPlaybackState)
    }

    @Test
    fun `parse group update with no playback state`() {
        val json = """{"type":"group/update","payload":{"volume":80}}"""
        val msg = parser.parseText(json) as GroupUpdate
        assertNull(msg.playbackState)
        assertNull(msg.typedPlaybackState)
    }

    @Test
    fun `unknown message type returns UnknownMessage`() {
        val msg = parser.parseText("""{"type":"future/feature","data":"something"}""")
        assertTrue(msg is UnknownMessage)
        assertEquals("future/feature", (msg as UnknownMessage).type)
    }

    @Test
    fun `invalid JSON returns null`() {
        val msg = parser.parseText("not json at all {{{")
        assertNull(msg)
    }

    // ── Outgoing message serialization ───────────────────────────────────────

    @Test
    fun `serialize client command play`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientCommand::class.java)
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = "play")
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""type":"client/command""""))
        assertTrue(json.contains(""""command":"play""""))
    }

    @Test
    fun `serialize client command mute uses muted field name`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientCommand::class.java)
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = "mute", muted = true)
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""muted":true"""))
    }

    @Test
    fun `serialize client command volume includes volume field`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientCommand::class.java)
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = "volume", volume = 75)
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""command":"volume""""))
        assertTrue(json.contains(""""volume":75"""))
    }

    @Test
    fun `serialize client state includes static_delay_ms in player object`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientStateMsg::class.java)
        val msg = ClientStateMsg(
            payload = ClientStateMsgPayload(
                player = PlayerStatePayload(staticDelayMs = 200)
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""type":"client/state""""))
        assertTrue(json.contains(""""state":"synchronized""""))
        assertTrue(json.contains(""""player":{"""))
        assertTrue(json.contains(""""static_delay_ms":200"""))
    }

    @Test
    fun `serialize client state with zero delay`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientStateMsg::class.java)
        val msg = ClientStateMsg(
            payload = ClientStateMsgPayload(
                player = PlayerStatePayload(staticDelayMs = 0)
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""static_delay_ms":0"""))
    }

    @Test
    fun `serialize client state player includes volume and muted when present`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientStateMsg::class.java)
        val msg = ClientStateMsg(
            payload = ClientStateMsgPayload(
                player = PlayerStatePayload(volume = 75, muted = false, staticDelayMs = 100)
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""volume":75"""))
        assertTrue(json.contains(""""muted":false"""))
        assertTrue(json.contains(""""static_delay_ms":100"""))
    }

    @Test
    fun `serialize client state includes timing fields`() {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ClientStateMsg::class.java)
        val msg = ClientStateMsg(
            payload = ClientStateMsgPayload(
                player = PlayerStatePayload(
                    staticDelayMs = 100,
                    requiredLeadTimeMs = 250,
                    minBufferMs = 500,
                )
            )
        )
        val json = adapter.toJson(msg)
        assertTrue(json.contains(""""static_delay_ms":100"""))
        assertTrue(json.contains(""""required_lead_time_ms":250"""))
        assertTrue(json.contains(""""min_buffer_ms":500"""))
    }

    @Test
    fun `parse server state with color`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "color": {
                  "timestamp": 5000000,
                  "background_dark":  [10, 20, 30],
                  "background_light": [200, 210, 220],
                  "primary":          [255, 0, 0],
                  "accent":           [0, 255, 0],
                  "on_dark":          [240, 240, 240],
                  "on_light":         [20, 20, 20]
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        val color = msg.color
        assertNotNull(color)
        assertEquals(5_000_000L, color!!.timestamp)
        assertEquals(listOf(10, 20, 30), color.backgroundDark)
        assertEquals(listOf(200, 210, 220), color.backgroundLight)
        assertEquals(listOf(255, 0, 0), color.primary)
        assertEquals(listOf(0, 255, 0), color.accent)
        assertEquals(listOf(240, 240, 240), color.onDark)
        assertEquals(listOf(20, 20, 20), color.onLight)
    }

    @Test
    fun `parse server state color with null fields`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "color": {
                  "timestamp": 1000,
                  "primary": [128, 64, 32]
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        val color = msg.color
        assertNotNull(color)
        assertEquals(1000L, color!!.timestamp)
        assertEquals(listOf(128, 64, 32), color.primary)
        assertNull(color.backgroundDark)
        assertNull(color.backgroundLight)
        assertNull(color.accent)
        assertNull(color.onDark)
        assertNull(color.onLight)
    }

    @Test
    fun `parse server state controller with repeat and shuffle`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "controller": {
                  "supported_commands": ["volume"],
                  "volume": 80,
                  "muted": false,
                  "repeat": "all",
                  "shuffle": true
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Present("all"), msg.controller?.repeat)
        assertEquals(JsonOptional.Present(true), msg.controller?.shuffle)
        assertEquals(80, msg.controller?.volume)
    }

    @Test
    fun `parse server state controller without repeat and shuffle defaults to Absent`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "controller": {
                  "volume": 100,
                  "muted": false
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Absent, msg.controller?.repeat)
        assertEquals(JsonOptional.Absent, msg.controller?.shuffle)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `parse server state metadata with legacy repeat and shuffle`() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "metadata": {
                  "title": "Old Server Track",
                  "repeat": "one",
                  "shuffle": false
                }
              }
            }
        """.trimIndent()

        val msg = parser.parseText(json) as ServerState
        assertEquals(JsonOptional.Present("one"), msg.metadata?.repeat)
        assertEquals(JsonOptional.Present(false), msg.metadata?.shuffle)
    }

    @Test
    fun `parse stream end with versioned role names`() {
        val msg = parser.parseText(
            """{"type":"stream/end","payload":{"roles":["player@v1","metadata@v1"]}}"""
        ) as StreamEnd
        assertEquals(listOf("player@v1", "metadata@v1"), msg.roles)
    }

    // ── Binary messages ───────────────────────────────────────────────────────

    @Test
    fun `parse binary audio chunk`() {
        val timestamp = 123_456_789_000L
        val audioData = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val frame = buildBinaryFrame(BINARY_TYPE_AUDIO.toByte(), timestamp, audioData)
        val result = parser.parseBinary(frame) as MessageParser.BinaryAudio

        assertEquals(timestamp, result.chunk.serverTimestampMicros)
        assertTrue(audioData.contentEquals(result.chunk.data))
    }

    @Test
    fun `parse binary artwork channel 0`() {
        val timestamp = 9_999L
        val imgData = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // JPEG magic

        val frame = buildBinaryFrame(BINARY_TYPE_ARTWORK_0.toByte(), timestamp, imgData)
        val result = parser.parseBinary(frame) as MessageParser.BinaryArtwork

        assertEquals(0, result.channel)
        assertEquals(timestamp, result.serverTimestampMicros)
        assertTrue(imgData.contentEquals(result.data))
    }

    @Test
    fun `binary frame too short returns null`() {
        val tooShort = ByteArray(4) { 0x04 }.toByteString()
        val result = parser.parseBinary(tooShort)
        assertNull(result)
    }

    @Test
    fun `binary frame empty payload is valid audio chunk`() {
        val frame = buildBinaryFrame(BINARY_TYPE_AUDIO.toByte(), 0L, ByteArray(0))
        val result = parser.parseBinary(frame) as MessageParser.BinaryAudio
        assertEquals(0, result.chunk.data.size)
    }

    // ── Visualizer binary messages ────────────────────────────────────────────

    @Test
    fun `parse visualizer loudness`() {
        val ts = 500_000L
        val payload = byteArrayOf(0x80.toByte(), 0x00.toByte()) // 32768
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_LOUDNESS, ts, payload))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Loudness
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(32768, frame.value)
    }

    @Test
    fun `visualizer loudness too short returns null`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_LOUDNESS, 0L, byteArrayOf(0x01)))
        assertNull(result)
    }

    @Test
    fun `parse visualizer beat without downbeat`() {
        val ts = 1_000_000L
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, ts, byteArrayOf(0x00)))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Beat
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(false, frame.isDownbeat)
    }

    @Test
    fun `parse visualizer beat with downbeat`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, 0L, byteArrayOf(0x01)))
            as MessageParser.BinaryVisualizer
        assertEquals(true, (result.frame as VisualizerFrame.Beat).isDownbeat)
    }

    @Test
    fun `visualizer beat too short returns null`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, 0L, byteArrayOf()))
        assertNull(result)
    }

    @Test
    fun `parse visualizer f_peak`() {
        val ts = 2_000_000L
        val payload = byteArrayOf(0x04, 0x40.toByte(), 0xFF.toByte(), 0xFF.toByte()) // freq=1088, amp=65535
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_F_PEAK, ts, payload))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.FPeak
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0x0440, frame.freqHz)
        assertEquals(65535, frame.amplitude)
    }

    @Test
    fun `visualizer f_peak too short returns null`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_F_PEAK, 0L, byteArrayOf(0x01, 0x02)))
        assertNull(result)
    }

    @Test
    fun `parse visualizer spectrum`() {
        val ts = 3_000_000L
        val payload = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFF.toByte()) // bins: [1, 65535]
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_SPECTRUM, ts, payload))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Spectrum
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(2, frame.bins.size)
        assertEquals(1.toShort(), frame.bins[0])
        assertEquals((-1).toShort(), frame.bins[1]) // 0xFFFF as short
    }

    @Test
    fun `parse visualizer spectrum with empty payload`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_SPECTRUM, 0L, byteArrayOf()))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Spectrum
        assertEquals(0, frame.bins.size)
    }

    @Test
    fun `parse visualizer peak`() {
        val ts = 4_000_000L
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PEAK, ts, byteArrayOf(0xAB.toByte())))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Peak
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0xAB, frame.strength)
    }

    @Test
    fun `visualizer peak too short returns null`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PEAK, 0L, byteArrayOf()))
        assertNull(result)
    }

    @Test
    fun `parse visualizer pitch`() {
        val ts = 5_000_000L
        // midi = 0x4580 (MIDI 69.5 ≈ A4+half), confidence = 200
        val payload = byteArrayOf(0x45, 0x80.toByte(), 0xC8.toByte())
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PITCH, ts, payload))
            as MessageParser.BinaryVisualizer
        val frame = result.frame as VisualizerFrame.Pitch
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0x4580, frame.midiFixed88)
        assertEquals(200, frame.confidence)
    }

    @Test
    fun `visualizer pitch too short returns null`() {
        val result = parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PITCH, 0L, byteArrayOf(0x45, 0x80.toByte())))
        assertNull(result)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildBinaryFrame(type: Byte, timestamp: Long, payload: ByteArray): ByteString {
        val buf = ByteBuffer.allocate(1 + 8 + payload.size)
        buf.put(type)
        buf.putLong(timestamp)
        buf.put(payload)
        return buf.array().toByteString()
    }
}
