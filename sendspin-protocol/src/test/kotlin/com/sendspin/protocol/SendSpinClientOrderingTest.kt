package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the "two songs mixed chunk-by-chunk after a skip" bug. On a skip the wire order is
 * `…old audio chunk → stream/start → new audio chunk…`. OkHttp delivers all three on one reader
 * thread, so the discontinuity buffer clear in the stream/start handler must run synchronously on
 * that thread to land between the old and the new chunk.
 *
 * Before the fix the clear was delegated to the host AudioPlayer via transition()/configure(), and
 * the stream/start handler only did `audioScope.launch { … }`. The clear therefore ran later, on the
 * audio thread, and could wipe new-stream chunks that had already been offered — or miss the old one
 * entirely, so the two streams interleaved. This test fails on that code path.
 */
class SendSpinClientOrderingTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val preferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("pcm", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private fun buildClient() = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        clientId = "test-id",
        clientName = "Test",
        manufacturer = "Acme",
        productName = "TestDevice",
        softwareVersion = "1",
        audioPlayerFactory = { _, _ ->
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
        },
    )

    private fun audioFrame(ts: Long, payload: ByteArray): okio.ByteString {
        val out = ByteArray(9 + payload.size)
        out[0] = BINARY_TYPE_AUDIO
        for (i in 0 until 8) out[1 + i] = (ts ushr (8 * (7 - i))).toByte() // big-endian int64
        payload.copyInto(out, destinationOffset = 9)
        return out.toByteString()
    }

    @Test
    fun `stream start synchronously drops the old chunk before the new one is offered`() {
        val client = buildClient() // fresh: clock offset 0, so toLocalMicros is the identity
        val now = ClockSync.localMicros()
        val oldPayload = byteArrayOf(1, 1, 1)
        val newPayload = byteArrayOf(2, 2, 2)

        // Old-stream chunk, scheduled ≈ now so it is admitted, not dropped as late or far-future.
        client.handleBinaryMessage(audioFrame(now, oldPayload))
        assertEquals("old chunk should be buffered before stream/start", 1, client.audioBuffer.size)

        // stream/start is a discontinuity, so it must clear the buffer synchronously.
        client.handleTextMessage(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}}}"""
        )
        assertEquals("stream/start must synchronously flush the old chunk", 0, client.audioBuffer.size)

        // A new-stream chunk offered after the clear survives.
        client.handleBinaryMessage(audioFrame(now + 1_000, newPayload))
        assertEquals("only the new chunk survives", 1, client.audioBuffer.size)

        val survivor = client.audioBuffer.poll(now + 2_000)
        assertEquals("survivor must be the NEW chunk", newPayload.toList(), survivor?.data?.toList())
    }
}
