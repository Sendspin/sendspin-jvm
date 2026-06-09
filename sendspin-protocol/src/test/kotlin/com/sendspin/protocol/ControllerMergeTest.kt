package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests the repeat/shuffle merge logic: controller values take precedence over metadata
 * (legacy) values, with metadata as a fallback for old servers that don't send controller state.
 */
@Suppress("DEPRECATION")
class ControllerMergeTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
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

    private lateinit var client: SendSpinClient

    @Before
    fun setUp() {
        client = SendSpinClient(
            okHttpClient = OkHttpClient.Builder().build(),
            moshi = moshi,
            preferences = defaultPreferences,
            clientId = "test-id",
            clientName = "Test",
            manufacturer = "Acme",
            productName = "TestDevice",
            softwareVersion = "1",
            audioPlayerFactory = noOpPlayerFactory,
        )
    }

    @Test
    fun `new server - repeat and shuffle from controller are used`() {
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 80, "muted": false, "repeat": "all", "shuffle": true }
              }
            }
        """.trimIndent())

        val ctrl = client.controllerState.value!!
        assertEquals(JsonOptional.Present("all"), ctrl.repeat)
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)
    }

    @Test
    fun `old server - controller with volume only, metadata supplies repeat and shuffle`() {
        // Old server sends controller for volume/muted alongside metadata for repeat/shuffle.
        // Controller repeat/shuffle are Absent, so metadata values should be used.
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 75, "muted": false },
                "metadata": { "repeat": "one", "shuffle": true }
              }
            }
        """.trimIndent())

        val ctrl = client.controllerState.value!!
        assertEquals(JsonOptional.Present("one"), ctrl.repeat)
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)
        assertEquals(75, ctrl.volume)
    }

    @Test
    fun `old server - metadata repeat and shuffle update existing controller state`() {
        // Establish controller state first (old server sends controller at connection time)
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 75, "muted": false }
              }
            }
        """.trimIndent())

        // Then update repeat/shuffle via legacy metadata path
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "metadata": { "title": "Track", "repeat": "one", "shuffle": false }
              }
            }
        """.trimIndent())

        val ctrl = client.controllerState.value!!
        assertEquals(JsonOptional.Present("one"), ctrl.repeat)
        assertEquals(JsonOptional.Present(false), ctrl.shuffle)
        assertEquals(75, ctrl.volume)  // existing volume preserved
    }

    @Test
    fun `old server - metadata repeat without prior controller state is ignored`() {
        // No controller state has been received yet; metadata-only cannot invent volume/muted
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "metadata": { "repeat": "all", "shuffle": true }
              }
            }
        """.trimIndent())

        assertNull(client.controllerState.value)
    }

    @Test
    fun `controller repeat wins over metadata repeat when both present`() {
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 100, "muted": false, "repeat": "all" },
                "metadata": { "repeat": "one" }
              }
            }
        """.trimIndent())

        assertEquals(JsonOptional.Present("all"), client.controllerState.value!!.repeat)
    }

    @Test
    fun `controller null repeat is not overridden by metadata repeat`() {
        // Controller is authoritative: Present(null) from controller means cleared.
        // Metadata repeat must not re-populate it.
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 100, "muted": false, "repeat": null },
                "metadata": { "repeat": "one" }
              }
            }
        """.trimIndent())

        assertEquals(JsonOptional.Present(null), client.controllerState.value!!.repeat)
    }

    @Test
    fun `old server - metadata explicit null clears previously set repeat`() {
        // Establish a controller state with repeat set
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 80, "muted": false, "repeat": "all" }
              }
            }
        """.trimIndent())
        assertEquals(JsonOptional.Present("all"), client.controllerState.value!!.repeat)

        // Old server sends explicit null to clear repeat
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "metadata": { "repeat": null }
              }
            }
        """.trimIndent())

        assertEquals(JsonOptional.Present(null), client.controllerState.value!!.repeat)
    }

    @Test
    fun `old server - metadata explicit null clears previously set shuffle`() {
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 80, "muted": false, "shuffle": true }
              }
            }
        """.trimIndent())
        assertEquals(JsonOptional.Present(true), client.controllerState.value!!.shuffle)

        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "metadata": { "shuffle": null }
              }
            }
        """.trimIndent())

        assertEquals(JsonOptional.Present(null), client.controllerState.value!!.shuffle)
    }

    @Test
    fun `controller volume-only update preserves previously merged repeat and shuffle`() {
        // Establish repeat/shuffle via new-server controller message
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 80, "muted": false, "repeat": "all", "shuffle": true }
              }
            }
        """.trimIndent())
        assertEquals(JsonOptional.Present("all"), client.controllerState.value!!.repeat)

        // Volume-only update: repeat/shuffle absent from both controller and metadata
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "controller": { "volume": 50, "muted": false }
              }
            }
        """.trimIndent())

        val ctrl = client.controllerState.value!!
        assertEquals(50, ctrl.volume)
        assertEquals(JsonOptional.Present("all"), ctrl.repeat)   // must be preserved
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)   // must be preserved
    }

    @Test
    fun `no repeat or shuffle in either source leaves controller state null`() {
        client.handleTextMessage("""
            {
              "type": "server/state",
              "payload": {
                "metadata": { "title": "Track" }
              }
            }
        """.trimIndent())

        assertNull(client.controllerState.value)
    }
}
