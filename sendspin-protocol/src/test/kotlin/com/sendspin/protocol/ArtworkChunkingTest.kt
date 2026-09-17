package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Spec PR #188: artwork is transferred as an announce (timestamp + total_size, no data) followed
 * by parts carrying only image bytes, replacing the old single-message-per-image binary format.
 */
class ArtworkChunkingTest {

    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val preferences = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = listOf(
            ArtworkChannelConfig("album",  "jpeg", 800, 800),
            ArtworkChannelConfig("artist", "jpeg", 800, 800),
        ),
        supportedOptionalRoles = setOf(OptionalRole.ARTWORK),
    )

    private class RecordingSocket : SendSpinWebSocket {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        var closed = false
        var closeReason: String? = null
        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }
        override fun close(code: Int, reason: String?) {
            closed = true
            closeReason = reason
        }
    }

    private fun buildClient() = SendSpinClient(
        okHttpClient = OkHttpClient.Builder().build(),
        moshi = moshi,
        preferences = preferences,
        clientId = "test-id",
        clientName = "Test",
        manufacturer = "Acme",
        productName = "TestDevice",
        softwareVersion = "1",
        audioPlayerFactory = null,
    )

    private fun connect(client: SendSpinClient, socket: RecordingSocket, activeRoles: List<String> = listOf("artwork@v1")) {
        client.acceptIncomingConnection(
            socket,
            ServerHello(serverId = "srv-1", name = "Test Server", version = 1, activeRoles = activeRoles),
        )
    }

    private fun announce(channelType: Byte, timestamp: Long, totalSize: Int): ByteString {
        val buf = ByteBuffer.allocate(14)
        buf.put(channelType)
        buf.put(ARTWORK_FLAG_ANNOUNCE.toByte())
        buf.putLong(timestamp)
        buf.putInt(totalSize)
        return buf.array().toByteString()
    }

    private fun part(channelType: Byte, data: ByteArray): ByteString {
        val buf = ByteBuffer.allocate(2 + data.size)
        buf.put(channelType)
        buf.put(0.toByte())
        buf.put(data)
        return buf.array().toByteString()
    }

    private fun cancel(channelType: Byte): ByteString =
        byteArrayOf(channelType, ARTWORK_FLAG_CANCEL.toByte()).toByteString()

    @Test
    fun `announce plus parts becomes the current image once total_size is reached`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val imageData = byteArrayOf(1, 2, 3, 4, 5)
        val past = ClockSync.localMicros() - 1_000_000L

        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, imageData.size))
        assertNull("image should not be current before any parts arrive", client.albumArtwork.value)

        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, imageData.sliceArray(0..2)))
        assertNull("image should not be current until total_size is reached", client.albumArtwork.value)

        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, imageData.sliceArray(3..4)))
        assertArrayEquals(imageData, client.albumArtwork.value)
        assertTrue("connection must stay open on a well-formed transfer", !socket.closed)
    }

    @Test
    fun `empty announce (total_size 0) clears the current image`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 5))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1, 2, 3, 4, 5)))
        assertTrue(client.albumArtwork.value != null)

        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 0))

        assertNull("total_size 0 announce clears the channel's current image", client.albumArtwork.value)
    }

    @Test
    fun `cancel discards a partly-received image without touching the current image`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L
        // Establish a current image first.
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 2))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(9, 9)))
        assertArrayEquals(byteArrayOf(9, 9), client.albumArtwork.value)

        // Start (but don't finish) a replacement transfer, then cancel it.
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 100))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1, 2, 3)))
        client.handleBinaryMessage(cancel(BINARY_TYPE_ARTWORK_0))

        assertArrayEquals("current image must be unaffected by cancel", byteArrayOf(9, 9), client.albumArtwork.value)
        assertTrue(!socket.closed)

        // The cancelled transfer must no longer be in flight: a fresh announce+part must work.
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 1))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(7)))
        assertArrayEquals(byteArrayOf(7), client.albumArtwork.value)
    }

    @Test
    fun `future timestamp becomes the pending image until the timestamp is reached`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val future = ClockSync.localMicros() + 200_000L // 200ms ahead
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, future, 3))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1, 2, 3)))

        assertNull("future-timestamped image must not become current immediately", client.albumArtwork.value)

        Thread.sleep(400)

        assertArrayEquals("image must become current once its timestamp is reached", byteArrayOf(1, 2, 3), client.albumArtwork.value)
    }

    @Test
    fun `part with no transfer in flight is a malformed sequence and closes the connection`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)

        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1)))

        assertTrue("client must close the connection on a malformed artwork sequence", socket.closed)
    }

    @Test
    fun `announce while a transfer is in flight is malformed and closes the connection`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 10))

        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_1, past, 5))

        assertTrue("a second announce while one is in flight must close the connection", socket.closed)
    }

    @Test
    fun `part data extending past total_size is malformed and closes the connection`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 2))

        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1, 2, 3)))

        assertTrue(socket.closed)
    }

    @Test
    fun `stream-end for artwork clears current image and aborts an in-flight transfer`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 2))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), client.albumArtwork.value)

        client.handleTextMessage("""{"type":"stream/end","payload":{"roles":["artwork@v1"]}}""")

        assertNull("current image must be cleared on artwork stream/end", client.albumArtwork.value)

        // The (aborted) in-flight transfer's channel must accept a fresh announce afterwards.
        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_0, past, 1))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_0, byteArrayOf(5)))
        assertArrayEquals(byteArrayOf(5), client.albumArtwork.value)
    }

    @Test
    fun `artwork channel routes to album or artist by declared source`() {
        val client = buildClient()
        val socket = RecordingSocket()
        connect(client, socket)
        val past = ClockSync.localMicros() - 1_000_000L

        client.handleBinaryMessage(announce(BINARY_TYPE_ARTWORK_1, past, 1))
        client.handleBinaryMessage(part(BINARY_TYPE_ARTWORK_1, byteArrayOf(42)))

        assertArrayEquals(byteArrayOf(42), client.artistArtwork.value)
        assertNull(client.albumArtwork.value)
    }
}
