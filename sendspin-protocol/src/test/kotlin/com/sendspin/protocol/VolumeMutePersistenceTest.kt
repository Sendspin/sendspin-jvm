package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMutePersistenceTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val preferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("pcm", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private val noOpPlayerFactory: (AudioBuffer, ClockSync) -> AudioPlayer = { _, _ ->
        object : AudioPlayer {
            override val isPlaying = false
            override val droppedDecodeFrames = 0L
            override fun configure(format: StreamFormat) {}
            override fun start() {}
            override fun flushSink() {}
            override fun stop() {}
            override fun transition(format: StreamFormat) {}
            override fun setVolume(gain: Float) {}
        }
    }

    private class CapturingAudioPlayer : AudioPlayer {
        var lastGain: Float? = null
        override val isPlaying = false
        override val droppedDecodeFrames = 0L
        override fun configure(format: StreamFormat) {}
        override fun start() {}
        override fun flushSink() {}
        override fun stop() {}
        override fun transition(format: StreamFormat) {}
        override fun setVolume(gain: Float) { lastGain = gain }
    }

    private class FakeSettingsStore : ClientSettingsStore {
        val ints = mutableMapOf<String, Int>()
        val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
    }

    private fun buildClient(settingsStore: ClientSettingsStore) = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        manufacturer = "Acme",
        productName = "SmartTV-9000",
        softwareVersion = "14",
        audioPlayerFactory = noOpPlayerFactory,
        settingsStore = settingsStore,
    )

    @Test
    fun `server command volume persists value to settings store`() {
        val store = FakeSettingsStore()
        val client = buildClient(store)

        client.handleTextMessage(
            """{"type":"server/command","payload":{"player":{"command":"volume","volume":42}}}"""
        )

        assertEquals(42, store.ints[ClientSettingsKeys.PLAYER_VOLUME])
    }

    @Test
    fun `server command mute persists value to settings store`() {
        val store = FakeSettingsStore()
        val client = buildClient(store)

        client.handleTextMessage(
            """{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}"""
        )

        assertEquals(1, store.ints[ClientSettingsKeys.PLAYER_MUTED])
    }

    @Test
    fun `muted is loaded from settings store on construction and applied on server hello`() {
        val store = FakeSettingsStore()
        store.ints[ClientSettingsKeys.PLAYER_VOLUME] = 33
        store.ints[ClientSettingsKeys.PLAYER_MUTED] = 1

        val player = CapturingAudioPlayer()
        val client = SendSpinClient(
            okHttpClient = OkHttpClient.Builder().build(),
            moshi = moshi,
            preferences = preferences,
            manufacturer = "Acme",
            productName = "SmartTV-9000",
            softwareVersion = "14",
            audioPlayerFactory = { _, _ -> player },
            settingsStore = store,
            reconnectEnabled = false,
        )

        client.handleTextMessage(
            """{"type":"server/hello","payload":{"server_id":"srv1","name":"Server","version":1,"active_roles":["player@v1"]}}"""
        )

        // Persisted muted=true must force zero gain regardless of the persisted volume.
        assertEquals(0f, player.lastGain)
    }
}
