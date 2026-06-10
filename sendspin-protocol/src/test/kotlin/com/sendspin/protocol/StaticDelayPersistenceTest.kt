package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class StaticDelayPersistenceTest {

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
            override fun flush() {}
            override fun stop() {}
            override fun transition(format: StreamFormat) {}
            override fun setVolume(gain: Float) {}
        }
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
    fun `setStaticDelayMs persists value to settings store`() {
        val store = FakeSettingsStore()
        val client = buildClient(store)

        client.setStaticDelayMs(1500)

        assertEquals(1500, store.ints[ClientSettingsKeys.STATIC_DELAY_MS])
    }

    @Test
    fun `static delay is loaded from settings store on construction`() {
        val store = FakeSettingsStore()
        store.ints[ClientSettingsKeys.STATIC_DELAY_MS] = 1500

        val client = buildClient(store)

        assertEquals(1_500_000L, client.audioBuffer.staticDelayMicros)
    }
}
