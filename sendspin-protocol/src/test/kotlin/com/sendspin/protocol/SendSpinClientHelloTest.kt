package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SendSpinClientHelloTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(
            AudioFormat("flac", 2, 48000, 16),
            AudioFormat("opus", 2, 48000, 16),
            AudioFormat("pcm",  2, 48000, 16),
        ),
        artworkChannels = listOf(
            ArtworkChannelConfig("album",  "jpeg", 800, 800),
            ArtworkChannelConfig("artist", "jpeg", 1920, 1080),
        ),
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

    private fun buildClient(
        clientId: String = "test-id",
        clientName: String = "Test TV",
        manufacturer: String = "Acme",
        productName: String = "SmartTV-9000",
        softwareVersion: String = "14",
        macAddress: String? = null,
        preferences: ClientPreferences = defaultPreferences,
        settingsStore: ClientSettingsStore = NoOpClientSettingsStore,
        audioPlayerFactory: ((AudioBuffer, ClockSync) -> AudioPlayer)? = noOpPlayerFactory,
    ) = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        clientId = clientId,
        clientName = clientName,
        manufacturer = manufacturer,
        productName = productName,
        softwareVersion = softwareVersion,
        macAddress = macAddress,
        audioPlayerFactory = audioPlayerFactory,
        settingsStore = settingsStore,
    )

    private fun parseHello(json: String): ClientHello =
        moshi.adapter(ClientHello::class.java).fromJson(json)!!

    private class RecordingSocket : SendSpinWebSocket {
        // Background coroutines (clock sync, periodic state reports) call send() concurrently
        // with the test thread reading `sent`, so this must be a thread-safe collection.
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }
        override fun close(code: Int, reason: String?) {}
    }

    @Test
    fun `buildClientHelloJson includes manufacturer, product_name, and software_version in device_info`() {
        val json = buildClient(
            manufacturer = "Acme",
            productName = "SmartTV-9000",
            softwareVersion = "14",
        ).buildClientHelloJson()

        val deviceInfo = parseHello(json).payload.deviceInfo
        assertNotNull("device_info missing from hello payload", deviceInfo)
        assertEquals("Acme", deviceInfo!!.manufacturer)
        assertEquals("SmartTV-9000", deviceInfo.productName)
        assertEquals("14", deviceInfo.softwareVersion)
    }

    @Test
    fun `buildClientHelloJson includes client_id and name`() {
        val json = buildClient(clientId = "my-id", clientName = "Living Room TV").buildClientHelloJson()

        val payload = parseHello(json).payload
        assertEquals("my-id", payload.clientId)
        assertEquals("Living Room TV", payload.name)
    }

    @Test
    fun `buildClientHelloJson omits artwork@v1_support (spec PR #195 moved channels to client-state)`() {
        val json = buildClient().buildClientHelloJson()
        assertTrue(
            "artwork@v1_support must not appear in client/hello",
            !json.contains(""""artwork@v1_support""""),
        )
    }

    @Test
    fun `client-state reports artwork channels from preferences when the role is active`() {
        val channels = listOf(
            ArtworkChannelConfig("album",  "jpeg", 800,  800),
            ArtworkChannelConfig("artist", "jpeg", 1920, 1080),
        )
        val client = buildClient(preferences = defaultPreferences.copy(artworkChannels = channels))
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("artwork@v1")),
        )
        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .first { it.type == "client/state" }
        assertEquals(channels, stateMsg.payload.artwork!!.channels)
    }

    @Test
    fun `setArtworkChannels updates the reported configuration`() {
        val client = buildClient()
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("artwork@v1")),
        )
        val updated = listOf(ArtworkChannelConfig("none"))

        client.setArtworkChannels(updated)

        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .last { it.type == "client/state" }
        assertEquals(updated, stateMsg.payload.artwork!!.channels)
    }

    @Test
    fun `buildClientHelloJson includes controller@v1_support as empty object`() {
        val json = buildClient().buildClientHelloJson()
        assertTrue(
            "controller@v1_support missing from hello payload",
            json.contains(""""controller@v1_support""""),
        )
        val payload = parseHello(json).payload
        assertNotNull("controller@v1_support deserialized as null", payload.controllerSupport)
    }

    @Test
    fun `stream-end with versioned player@v1 role clears streamFormat`() {
        val client = buildClient()
        client.handleTextMessage(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}}}"""
        )
        assertNotNull("streamFormat should be set after stream/start", client.streamFormat.value)
        client.handleTextMessage(
            """{"type":"stream/end","payload":{"roles":["player@v1"]}}"""
        )
        assertNull("streamFormat should be null after stream/end with player@v1", client.streamFormat.value)
    }

    @Test
    fun `buildClientHelloJson advertises color@v1 in supported_roles`() {
        val json = buildClient().buildClientHelloJson()
        val payload = parseHello(json).payload
        assertTrue("color@v1 missing from supported_roles", payload.supportedRoles.contains("color@v1"))
        assertTrue("color@v1_support missing from hello payload", json.contains(""""color@v1_support""""))
        assertNotNull("color@v1_support deserialized as null", payload.colorSupport)
    }

    @Test
    fun `stream-end with color@v1 role clears colorState`() {
        val client = buildClient()
        client.handleTextMessage(
            """{"type":"server/state","payload":{"color":{"timestamp":1000,"primary":[255,0,0]}}}"""
        )
        assertNotNull("colorState should be set after server/state with color", client.colorState.value)
        client.handleTextMessage(
            """{"type":"stream/end","payload":{"roles":["color@v1"]}}"""
        )
        assertNull("colorState should be null after stream/end with color@v1", client.colorState.value)
    }

    @Test
    fun `server-state explicit null color clears colorState`() {
        val client = buildClient()
        client.handleTextMessage(
            """{"type":"server/state","payload":{"color":{"timestamp":1000,"primary":[255,0,0]}}}"""
        )
        assertNotNull("colorState should be set after server/state with color", client.colorState.value)

        client.handleTextMessage("""{"type":"server/state","payload":{"color":null}}""")

        assertNull("explicit null color object must clear colorState per spec", client.colorState.value)
    }

    @Test
    fun `server-state omitting color leaves colorState unchanged`() {
        val client = buildClient()
        client.handleTextMessage(
            """{"type":"server/state","payload":{"color":{"timestamp":1000,"primary":[255,0,0]}}}"""
        )
        assertNotNull(client.colorState.value)

        // metadata-only update, no "color" key at all — must not touch colorState.
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"title":"Track"}}}""")

        assertNotNull("omitted color role must leave prior state untouched", client.colorState.value)
        assertEquals(1000L, client.colorState.value!!.timestamp)
    }

    @Test
    fun `buildClientHelloJson includes mac_address when provided`() {
        val json = buildClient(macAddress = "AA:BB:CC:DD:EE:FF").buildClientHelloJson()
        assertEquals("AA:BB:CC:DD:EE:FF", parseHello(json).payload.macAddress)
    }

    @Test
    fun `buildClientHelloJson omits mac_address when not provided`() {
        val json = buildClient().buildClientHelloJson()
        assertNull(parseHello(json).payload.macAddress)
    }

    @Test
    fun `buildClientHelloJson advertises visualizer@v1 when visualizerSupport is set`() {
        // Spec PR #195: hello's visualizer@v1_support keeps only buffer_capacity; types/rate_max/
        // spectrum moved to client/state (see `client-state reports visualizer request` below).
        val support = VisualizerSupport(bufferCapacity = 65536)
        val json = buildClient(
            preferences = defaultPreferences.copy(visualizerSupport = support),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload
        assertTrue("visualizer@v1 missing from supported_roles", payload.supportedRoles.contains("visualizer@v1"))
        assertNotNull("visualizer@v1_support missing", payload.visualizerSupport)
        assertEquals(65536, payload.visualizerSupport!!.bufferCapacity)
    }

    @Test
    fun `client-state reports visualizer request when the role is active`() {
        val request = VisualizerStatePayload(
            types = listOf("loudness", "beat"),
            rateMax = 60,
        )
        val client = buildClient(
            preferences = defaultPreferences.copy(
                visualizerSupport = VisualizerSupport(bufferCapacity = 65536),
                visualizerRequest = request,
            ),
        )
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("visualizer@v1")),
        )
        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .first { it.type == "client/state" }
        assertEquals(request, stateMsg.payload.visualizer)
    }

    @Test
    fun `setVisualizerRequest updates the reported configuration`() {
        val client = buildClient(
            preferences = defaultPreferences.copy(visualizerSupport = VisualizerSupport(bufferCapacity = 65536)),
        )
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("visualizer@v1")),
        )
        val updated = VisualizerStatePayload(types = listOf("spectrum"), rateMax = 15, spectrum = VisualizerSpectrumConfig(32, "log", 20, 20000))

        client.setVisualizerRequest(updated)

        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .last { it.type == "client/state" }
        assertEquals(updated, stateMsg.payload.visualizer)
    }

    @Test
    fun `buildClientHelloJson omits visualizer@v1 when visualizerSupport is null`() {
        val json = buildClient().buildClientHelloJson()
        val payload = parseHello(json).payload
        assertTrue("visualizer@v1 should not be in supported_roles", !payload.supportedRoles.contains("visualizer@v1"))
        assertNull("visualizer@v1_support should be absent", payload.visualizerSupport)
    }

    @Test
    fun `stream-end with visualizer@v1 role clears visualizerStreamConfig`() {
        val client = buildClient(
            preferences = defaultPreferences.copy(
                visualizerSupport = VisualizerSupport(65536)
            )
        )
        client.handleTextMessage(
            """{"type":"stream/start","payload":{"visualizer":{"types":["loudness"],"rate_max":60}}}"""
        )
        assertNotNull("visualizerStreamConfig should be set after stream/start", client.visualizerStreamConfig.value)
        client.handleTextMessage(
            """{"type":"stream/end","payload":{"roles":["visualizer@v1"]}}"""
        )
        assertNull("visualizerStreamConfig should be null after stream/end with visualizer@v1", client.visualizerStreamConfig.value)
    }

    @Test
    fun `empty supportedOptionalRoles advertises no role and omits every support block`() {
        val json = buildClient(
            preferences = defaultPreferences.copy(
                visualizerSupport = VisualizerSupport(65536),
                supportedOptionalRoles = emptySet(),
            ),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload

        assertEquals("no role should be advertised", emptyList<String>(), payload.supportedRoles)
        assertNull("player support should be omitted", payload.playerSupport)
        assertNull("metadata support should be omitted", payload.metadataSupport)
        assertNull("controller support should be omitted", payload.controllerSupport)
        assertNull("color support should be omitted", payload.colorSupport)
        assertNull("visualizer support should be omitted", payload.visualizerSupport)
    }

    @Test
    fun `supportedOptionalRoles defaults to the full set and advertises the optional roles`() {
        val payload = parseHello(buildClient().buildClientHelloJson()).payload
        assertEquals(
            "default hello wire output must not move",
            listOf("player@v1", "metadata@v1", "artwork@v1", "controller@v1", "color@v1"),
            payload.supportedRoles,
        )
        assertNotNull(payload.playerSupport)
        assertNotNull(payload.metadataSupport)
        assertNotNull(payload.controllerSupport)
        assertNotNull(payload.colorSupport)
    }

    @Test
    fun `supportedOptionalRoles advertises only the selected subset in enum order`() {
        val json = buildClient(
            preferences = defaultPreferences.copy(
                supportedOptionalRoles = setOf(OptionalRole.CONTROLLER, OptionalRole.METADATA),
            ),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload

        assertEquals(
            listOf("metadata@v1", "controller@v1"),
            payload.supportedRoles,
        )
        assertNotNull("metadata support should be advertised", payload.metadataSupport)
        assertNotNull("controller support should be advertised", payload.controllerSupport)
        assertNull("player support should be omitted", payload.playerSupport)
        assertNull("color support should be omitted", payload.colorSupport)
        assertNull("visualizer support should be omitted", payload.visualizerSupport)
    }

    @Test
    fun `VISUALIZER in supportedOptionalRoles without visualizerSupport omits the role`() {
        val json = buildClient(
            preferences = defaultPreferences.copy(
                visualizerSupport = null,
                supportedOptionalRoles = setOf(OptionalRole.VISUALIZER),
            ),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload

        assertEquals("no role should be advertised", emptyList<String>(), payload.supportedRoles)
        assertNull("visualizer support should be omitted", payload.visualizerSupport)
    }

    @Test
    fun `visualizerSupport without VISUALIZER in supportedOptionalRoles omits the role`() {
        val json = buildClient(
            preferences = defaultPreferences.copy(
                visualizerSupport = VisualizerSupport(65536),
                supportedOptionalRoles = setOf(OptionalRole.METADATA),
            ),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload

        assertEquals(listOf("metadata@v1"), payload.supportedRoles)
        assertNull("visualizer support should be omitted", payload.visualizerSupport)
    }

    @Test
    fun `PLAYER absent from supportedOptionalRoles omits player@v1 and its support block`() {
        val json = buildClient(
            preferences = defaultPreferences.copy(
                supportedOptionalRoles = setOf(OptionalRole.CONTROLLER),
            ),
        ).buildClientHelloJson()
        val payload = parseHello(json).payload

        assertEquals(listOf("controller@v1"), payload.supportedRoles)
        assertNull("player@v1_support should be omitted", payload.playerSupport)
    }

    @Test
    fun `omitted audioPlayerFactory with PLAYER advertised fails fast`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildClient(audioPlayerFactory = null)
        }
        assertTrue(
            "message should name the conflicting settings, was: ${error.message}",
            error.message!!.contains("supportedOptionalRoles") &&
                error.message!!.contains("audioPlayerFactory"),
        )
    }

    @Test
    fun `omitted audioPlayerFactory without PLAYER falls back to NoOpAudioPlayer`() {
        val client = buildClient(
            preferences = defaultPreferences.copy(
                supportedOptionalRoles = setOf(OptionalRole.CONTROLLER),
            ),
            audioPlayerFactory = null,
        )
        assertEquals(NoOpAudioPlayer, client.audioPlayer)
    }

    @Test
    fun `player support advertises buffer_capacity from preferences`() {
        // supported_commands moved to client/state (spec PR #177) — see
        // `client-state reports supported_commands from preferences` below.
        val json = buildClient(
            preferences = defaultPreferences.copy(playerBufferCapacity = 524288),
        ).buildClientHelloJson()
        val support = parseHello(json).payload.playerSupport!!
        assertEquals(524288, support.bufferCapacity)
    }

    @Test
    fun `player support defaults preserve buffer_capacity`() {
        val support = parseHello(buildClient().buildClientHelloJson()).payload.playerSupport!!
        assertEquals(262144, support.bufferCapacity)
    }

    @Test
    fun `client-state reports supported_commands from preferences`() {
        val client = buildClient(
            preferences = defaultPreferences.copy(playerSupportedCommands = listOf("volume", "mute", "seek")),
        )
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1")),
        )
        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .first { it.type == "client/state" }
        assertEquals(listOf("volume", "mute", "seek"), stateMsg.payload.player!!.supportedCommands)
    }

    @Test
    fun `client-state supported_commands defaults to volume, mute, and set_output_delay`() {
        val client = buildClient()
        val socket = RecordingSocket()
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = listOf("player@v1")),
        )
        val stateMsg = socket.sent
            .mapNotNull { moshi.adapter(ClientStateMsg::class.java).fromJson(it) }
            .first { it.type == "client/state" }
        assertEquals(listOf("volume", "mute", "set_output_delay"), stateMsg.payload.player!!.supportedCommands)
    }

    @Test
    fun `buildClientHelloJson advertises exactly FLAC, Opus, PCM at 48kHz 16-bit in that order`() {
        val json = buildClient().buildClientHelloJson()

        val formats = parseHello(json).payload.playerSupport!!.supportedFormats
        assertEquals(
            listOf(
                AudioFormat("flac", 2, 48000, 16),
                AudioFormat("opus", 2, 48000, 16),
                AudioFormat("pcm",  2, 48000, 16),
            ),
            formats,
        )
        assertTrue("no 44100 Hz variants expected", formats.none { it.sampleRate == 44100 })
        assertTrue("no 24-bit variants expected",   formats.none { it.bitDepth == 24 })
    }
}
