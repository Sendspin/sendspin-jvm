package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            ArtworkChannel("album",  "jpeg", 800, 800),
            ArtworkChannel("artist", "jpeg", 1920, 1080),
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
        }
    }

    private fun buildClient(
        clientId: String = "test-id",
        clientName: String = "Test TV",
        manufacturer: String = "Acme",
        productName: String = "SmartTV-9000",
        softwareVersion: String = "14",
        preferences: ClientPreferences = defaultPreferences,
    ) = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        clientId = clientId,
        clientName = clientName,
        manufacturer = manufacturer,
        productName = productName,
        softwareVersion = softwareVersion,
        audioPlayerFactory = noOpPlayerFactory,
    )

    private fun parseHello(json: String): ClientHello =
        moshi.adapter(ClientHello::class.java).fromJson(json)!!

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
    fun `buildClientHelloJson advertises artwork channels from preferences`() {
        val channels = listOf(
            ArtworkChannel("album",  "jpeg", 800,  800),
            ArtworkChannel("artist", "jpeg", 1920, 1080),
        )
        val json = buildClient(
            preferences = defaultPreferences.copy(artworkChannels = channels),
        ).buildClientHelloJson()

        assertEquals(channels, parseHello(json).payload.artworkSupport!!.channels)
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
