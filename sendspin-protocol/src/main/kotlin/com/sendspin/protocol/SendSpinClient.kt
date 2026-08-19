package com.sendspin.protocol

import com.squareup.moshi.Moshi
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.sendspin.protocol.ProtocolLog as Timber
import java.util.UUID

enum class ClientState {
    IDLE, CONNECTING, HANDSHAKING, CLOCK_SYNCING, STREAMING, ERROR, DISCONNECTED
}

/**
 * Snapshot of internal diagnostics, emitted via [SendSpinClient.diagnostics].
 */
data class DiagnosticsSnapshot(
    val state: ClientState = ClientState.IDLE,
    val serverName: String = "",
    val clockOffsetMs: Double = 0.0,
    val clockDriftPpm: Double = 0.0,
    val lastRttMicros: Long = 0L,
    val bufferSize: Int = 0,
    val droppedChunks: Long = 0L,
    val lateChunks: Long = 0L,
    val droppedDecodeFrames: Long = 0L,
    val isAudioPlaying: Boolean = false,
)

/**
 * Roles a host can advertise in `client/hello`. The spec makes every role conditional, including
 * `player@v1`, so a controller-only or metadata-only host can leave [PLAYER] out.
 * Declaration order is the wire order of `supported_roles`.
 */
enum class OptionalRole(val roleName: String) {
    PLAYER("player@v1"),
    METADATA("metadata@v1"),
    ARTWORK("artwork@v1"),
    CONTROLLER("controller@v1"),
    COLOR("color@v1"),
    VISUALIZER("visualizer@v1"),
}

/**
 * Core SendSpin WebSocket client.
 *
 * Supports two connection modes:
 *  - CLIENT_INITIATED: app connects to a server (legacy path, via [connect])
 *  - SERVER_INITIATED: a server connects to us; [SendSpinServerHost] calls [acceptIncomingConnection]
 *
 * State machine: IDLE → CONNECTING → HANDSHAKING → CLOCK_SYNCING → STREAMING → DISCONNECTED
 *                                                                             ↘ ERROR (on failure)
 *
 * CLIENT_INITIATED: reconnects automatically with exponential backoff.
 * SERVER_INITIATED: waits for the server to reconnect — no client-side backoff.
 */
data class ClientPreferences(
    val supportedFormats: List<AudioFormat>,
    val artworkChannels: List<ArtworkChannel>,
    val visualizerSupport: VisualizerSupport? = null,
    /** Advertised `player@v1_support.buffer_capacity`. Defaults match [PlayerSupport]. */
    val playerBufferCapacity: Int = 262144,
    /** Advertised `player@v1_support.supported_commands`. Defaults match [PlayerSupport]. */
    val playerSupportedCommands: List<String> = listOf("volume", "mute"),
    /**
     * Roles to advertise in `client/hello`. A role that is absent here is omitted from
     * `supported_roles` together with its `*_support` block.
     * Defaults to every role — reduce the set for a host that implements only a subset,
     * or for a server that rejects a hello carrying roles it doesn't expect.
     * [OptionalRole.PLAYER] also needs a `SendSpinClient` audioPlayerFactory, and
     * [OptionalRole.VISUALIZER] a non-null [visualizerSupport], to take effect.
     */
    val supportedOptionalRoles: Set<OptionalRole> = OptionalRole.entries.toSet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClient(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val preferences: ClientPreferences,
    val clientId: String = UUID.randomUUID().toString(),
    private val clientName: String = "SendSpin TV",
    private val manufacturer: String,
    private val productName: String,
    private val softwareVersion: String,
    private val macAddress: String? = null,
    /** Omit only for a host that leaves [OptionalRole.PLAYER] out of its preferences. */
    audioPlayerFactory: ((AudioBuffer, ClockSync) -> AudioPlayer)? = null,
    /** Set to false to prevent automatic reconnection on disconnect (e.g. in conformance tests). */
    private val reconnectEnabled: Boolean = true,
    private val settingsStore: ClientSettingsStore = NoOpClientSettingsStore,
) {
    val clockSync = ClockSync()
    val audioBuffer = AudioBuffer(clockSync)
    val audioPlayer: AudioPlayer = audioPlayerFactory?.invoke(audioBuffer, clockSync) ?: run {
        require(OptionalRole.PLAYER !in preferences.supportedOptionalRoles) {
            "player@v1 is in supportedOptionalRoles but no audioPlayerFactory was provided"
        }
        NoOpAudioPlayer
    }

    private val parser = MessageParser(moshi)
    private val clientHelloAdapter    = moshi.adapter(ClientHello::class.java)
    private val clientTimeAdapter     = moshi.adapter(ClientTime::class.java)
    private val clientStateMsgAdapter = moshi.adapter(ClientStateMsg::class.java)
    private val clientGoodbyeAdapter  = moshi.adapter(ClientGoodbye::class.java)
    private val clientCommandAdapter  = moshi.adapter(ClientCommand::class.java)
    private val streamRequestFormatAdapter = moshi.adapter(StreamRequestFormat::class.java)

    // Tracks whether the first clock measurement has been processed. Used to flush the audio
    // buffer once (after the first offset estimate is available), even if server/state already
    // moved us to STREAMING before the first ServerTime arrived. Reset on each new connection.
    private var firstMeasurementCompleted = false

    // Buffers replies within the current burst so the lowest-RTT sample can be selected
    // (burst-then-best, see ClockSyncConfig.PROBES_PER_BURST). Now safe to do now that
    // AudioBuffer recomputes chunk schedules live from the current ClockSync estimate
    // instead of baking them in at arrival time (issue #10 step 1).
    private val burstReplies = mutableListOf<Pair<ServerTime, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var ws: SendSpinWebSocket? = null
    @Volatile private var activeOkHttpWs: WebSocket? = null
    private var clockJob: Job? = null
    private var stateJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var pendingStopJob: Job? = null

    private val _state = MutableStateFlow(ClientState.IDLE)
    val state: StateFlow<ClientState> = _state

    private val _serverState = MutableSharedFlow<ServerState>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serverState: SharedFlow<ServerState> = _serverState.asSharedFlow()

    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName

    private val _serverId = MutableStateFlow("")
    val serverId: StateFlow<String> = _serverId

    private val _serverHello = MutableStateFlow<ServerHello?>(null)
    /** The most recent [ServerHello] received from the connected server; null before first hello. */
    val serverHello: StateFlow<ServerHello?> = _serverHello

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics

    private val _groupPlaybackState = MutableStateFlow<GroupPlaybackState?>(null)
    val groupPlaybackState: StateFlow<GroupPlaybackState?> = _groupPlaybackState

    private val _streamFormat = MutableStateFlow<StreamFormat?>(null)
    val streamFormat: StateFlow<StreamFormat?> = _streamFormat

    private val _streamArtwork = MutableStateFlow<StreamArtworkConfig?>(null)
    /** Artwork channel configuration from the most recent `stream/start`; null until received. */
    val streamArtwork: StateFlow<StreamArtworkConfig?> = _streamArtwork

    private val _controllerState = MutableStateFlow<ControllerState?>(null)
    val controllerState: StateFlow<ControllerState?> = _controllerState

    private val _colorState = MutableStateFlow<ColorState?>(null)
    val colorState: StateFlow<ColorState?> = _colorState

    private val _visualizerStreamConfig = MutableStateFlow<StreamVisualizerConfig?>(null)
    val visualizerStreamConfig: StateFlow<StreamVisualizerConfig?> = _visualizerStreamConfig

    @Volatile private var staticDelayMs: Int = settingsStore.getInt(ClientSettingsKeys.STATIC_DELAY_MS, 0)
        .coerceIn(0, 5000)
    init {
        audioBuffer.staticDelayMicros = staticDelayMs * 1_000L
    }
    @Volatile private var requiredLeadTimeMs: Int = 0
    @Volatile private var minBufferMs: Int = 0

    // Per-player volume/mute, as set via server/command (player.command = "volume" | "mute").
    // Reported back to the server in client/state's player.volume / player.muted.
    // Persisted across restarts (spec PR #113: "persisting volume/muted across reboots is
    // RECOMMENDED"), matching the static_delay_ms precedent. Default to full volume / unmuted:
    // the spec requires these fields be present in client/state whenever "volume"/"mute" are in
    // supported_commands, even before the server has sent an explicit command.
    @Volatile private var playerVolume: Int = settingsStore.getInt(ClientSettingsKeys.PLAYER_VOLUME, 100)
        .coerceIn(0, 100)
    @Volatile private var playerMuted: Boolean = settingsStore.getInt(ClientSettingsKeys.PLAYER_MUTED, 0) != 0

    fun setStaticDelayMs(delayMs: Int) {
        staticDelayMs = delayMs.coerceIn(0, 5000)
        audioBuffer.staticDelayMicros = staticDelayMs * 1_000L
        settingsStore.putInt(ClientSettingsKeys.STATIC_DELAY_MS, staticDelayMs)
        sendClientState()
        // Chunks already in the buffer retain their old scheduled times; the new delay applies
        // only to newly arriving chunks. The transition resolves within one server buffer window
        // (~2 s). A full flush on delay change would cause a noticeable gap, so we accept the
        // gradual crossover.
    }

    fun setRequiredLeadTimeMs(ms: Int) {
        requiredLeadTimeMs = ms.coerceAtLeast(0)
        sendClientState()
    }

    fun setMinBufferMs(ms: Int) {
        minBufferMs = ms.coerceAtLeast(0)
        sendClientState()
    }

    private val _albumArtwork = MutableStateFlow<ByteArray?>(null)
    val albumArtwork: StateFlow<ByteArray?> = _albumArtwork

    private val _artistArtwork = MutableStateFlow<ByteArray?>(null)
    val artistArtwork: StateFlow<ByteArray?> = _artistArtwork

    /** The `connection_reason` from the most recent `server/hello`; null until first hello. */
    @Volatile var lastConnectionReason: String? = null
        private set

    fun toLocalMicros(serverMicros: Long): Long = clockSync.toLocalMicros(serverMicros)

    private var serverNameStr = ""
    private var connectUrl = ""
    private var reconnectAttempt = 0

    private enum class ConnectionMode { CLIENT_INITIATED, SERVER_INITIATED }
    private var connectionMode = ConnectionMode.CLIENT_INITIATED

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(wsUrl: String) {
        if (_state.value == ClientState.CONNECTING) return
        // Close any existing socket so its callbacks are ignored by the stale-socket guard.
        activeOkHttpWs = null
        ws?.close(1000, "replaced by new connection")
        ws = null
        cleanupJobs()
        connectUrl = wsUrl
        reconnectAttempt = 0
        connectionMode = ConnectionMode.CLIENT_INITIATED
        doConnect()
    }

    /**
     * Called by [SendSpinServerHost] once it has completed the `client/hello` ↔ `server/hello`
     * handshake on the incoming socket. Transitions directly to CLOCK_SYNCING.
     */
    internal fun acceptIncomingConnection(socket: SendSpinWebSocket, serverHello: ServerHello) {
        connectionMode = ConnectionMode.SERVER_INITIATED
        activeOkHttpWs = null  // invalidate any in-flight OkHttp callbacks
        val previous = ws
        ws = null
        if (previous != null && previous !== socket) {
            previous.close(1000, "replaced by incoming connection")
        }
        cleanupJobs()
        ws = socket
        clearSessionState()
        handleServerHello(serverHello)
    }

    fun sendControllerCommand(command: String, volume: Int? = null, muted: Boolean? = null) {
        val socket = ws
        if (socket == null) {
            Timber.w("SendSpinClient: cannot send client/command controller=%s; disconnected", command)
            return
        }
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = command, volume = volume, muted = muted)
            )
        )
        val queued = socket.send(clientCommandAdapter.toJson(msg))
        if (queued) Timber.d("SendSpinClient: >> client/command controller=%s volume=%s muted=%s", command, volume, muted)
        else Timber.w("SendSpinClient: failed to queue client/command controller=%s", command)
    }

    fun sendSeek(positionMs: Long) {
        val socket = ws
        if (socket == null) {
            Timber.w("SendSpinClient: cannot send client/command seek; disconnected")
            return
        }
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = "seek", positionMs = positionMs)
            )
        )
        val queued = socket.send(clientCommandAdapter.toJson(msg))
        if (queued) Timber.d("SendSpinClient: >> client/command seek positionMs=%d", positionMs)
        else Timber.w("SendSpinClient: failed to queue client/command seek")
    }

    fun sendSeekRelative(offsetMs: Long) {
        val socket = ws
        if (socket == null) {
            Timber.w("SendSpinClient: cannot send client/command seek_relative; disconnected")
            return
        }
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = "seek_relative", offsetMs = offsetMs)
            )
        )
        val queued = socket.send(clientCommandAdapter.toJson(msg))
        if (queued) Timber.d("SendSpinClient: >> client/command seek_relative offsetMs=%d", offsetMs)
        else Timber.w("SendSpinClient: failed to queue client/command seek_relative")
    }

    /**
     * Requests the server switch the player stream to a different format (e.g. to adapt to
     * changing network or CPU conditions). Unset parameters leave that aspect of the format
     * unchanged. The requested combination must be one advertised in [ClientPreferences.supportedFormats].
     */
    fun requestPlayerFormat(codec: String? = null, channels: Int? = null, sampleRate: Int? = null, bitDepth: Int? = null) {
        val socket = ws
        if (socket == null) {
            Timber.w("SendSpinClient: cannot send stream/request-format; disconnected")
            return
        }
        val msg = StreamRequestFormat(
            payload = StreamRequestFormatPayload(
                player = PlayerFormatRequest(codec = codec, channels = channels, sampleRate = sampleRate, bitDepth = bitDepth)
            )
        )
        val queued = socket.send(streamRequestFormatAdapter.toJson(msg))
        if (queued) {
            Timber.d(
                "SendSpinClient: >> stream/request-format player codec=%s channels=%s sampleRate=%s bitDepth=%s",
                codec, channels, sampleRate, bitDepth,
            )
        } else {
            Timber.w("SendSpinClient: failed to queue stream/request-format")
        }
    }

    fun disconnect(reason: String = "user_request") {
        activeOkHttpWs = null
        reconnectAttempt = Int.MAX_VALUE / 2
        cleanupJobs()
        clearSessionState()
        ws?.send(clientGoodbyeAdapter.toJson(ClientGoodbye(payload = ClientGoodbyePayload(reason = reason))))
        ws?.close(1000, reason)
        ws = null
        _state.value = ClientState.DISCONNECTED
    }

    /** JSON helpers used by [SendSpinServerHost] for the pending-handshake phase. */
    internal fun buildClientHelloJson(): String = clientHelloAdapter.toJson(buildClientHello())
    internal fun buildClientGoodbyeJson(reason: String): String =
        clientGoodbyeAdapter.toJson(ClientGoodbye(payload = ClientGoodbyePayload(reason = reason)))

    // ── Connection ────────────────────────────────────────────────────────────

    private fun doConnect() {
        _state.value = ClientState.CONNECTING
        Timber.i("SendSpinClient: connecting to %s", connectUrl)
        val request = Request.Builder().url(connectUrl).build()
        val rawWs = okHttpClient.newWebSocket(request, listener)
        activeOkHttpWs = rawWs
        ws = OkHttpWebSocketAdapter(rawWs)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== activeOkHttpWs) { webSocket.close(1000, "stale"); return }
            if (_state.value != ClientState.CONNECTING) {
                Timber.w("SendSpinClient: onOpen in unexpected state %s, closing", _state.value)
                webSocket.close(1000, "unexpected")
                return
            }
            Timber.i("SendSpinClient: WebSocket open")
            _state.value = ClientState.HANDSHAKING
            reconnectAttempt = 0
            clearSessionState()
            val adapter = OkHttpWebSocketAdapter(webSocket)
            ws = adapter
            sendHello(adapter)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== activeOkHttpWs) return
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== activeOkHttpWs) return
            handleBinaryMessage(bytes)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== activeOkHttpWs) return
            activeOkHttpWs = null
            ws = null
            Timber.e(t, "SendSpinClient: WebSocket failure")
            _state.value = ClientState.ERROR
            cleanupJobs()
            if (reconnectEnabled && connectionMode == ConnectionMode.CLIENT_INITIATED && reconnectAttempt < Int.MAX_VALUE / 2) {
                scheduleReconnect(immediate = false)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== activeOkHttpWs) return
            Timber.i("SendSpinClient: closing (%d %s)", code, reason)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== activeOkHttpWs) return
            activeOkHttpWs = null
            ws = null
            Timber.i("SendSpinClient: closed (%d %s)", code, reason)
            _state.value = ClientState.DISCONNECTED
            cleanupJobs()
            if (reconnectEnabled && connectionMode == ConnectionMode.CLIENT_INITIATED && reconnectAttempt < Int.MAX_VALUE / 2) {
                scheduleReconnect(immediate = code == 1000)
            }
        }
    }

    /** Called by [SendSpinServerHost] when the active server socket closes. */
    internal fun onServerSocketClosed(code: Int, reason: String) {
        Timber.i("SendSpinClient: server socket closed (%d %s)", code, reason)
        ws = null
        _state.value = ClientState.DISCONNECTED
        cleanupJobs()
        // SERVER_INITIATED mode: do not reconnect — wait for the server to reconnect.
    }

    /** Called by [SendSpinServerHost] on socket error. */
    internal fun onServerSocketError(ex: Exception) {
        Timber.e(ex, "SendSpinClient: server-socket error")
        ws = null
        _state.value = ClientState.ERROR
        cleanupJobs()
    }

    // ── Message handling ──────────────────────────────────────────────────────

    internal fun handleTextMessage(json: String) {
        Timber.d("SendSpinClient: << %s", json.take(120))
        when (val msg = parser.parseText(json)) {
            is ServerHello -> handleServerHello(msg)
            is ServerState -> {
                val title = (msg.metadata?.title as? JsonOptional.Present)?.value
                if (title != null) Timber.i("SendSpinClient: server/state title='%s'", title)
                val progress = msg.metadata?.progress
                if (progress != null) Timber.d("SendSpinClient: server/state progress=%dms speed=%d",
                    progress.trackProgress, progress.playbackSpeed)
                val effectiveController = mergeControllerWithMetadata(msg)
                if (effectiveController != null) {
                    _controllerState.value = effectiveController
                }
                if (msg.color != null) _colorState.value = msg.color
                _serverState.tryEmit(msg)
                if (_state.value == ClientState.CLOCK_SYNCING || _state.value == ClientState.STREAMING) {
                    _state.value = ClientState.STREAMING
                }
            }
            is ServerTime -> {
                val t4 = ClockSync.localMicros()
                burstReplies.add(msg to t4)
                if (burstReplies.size >= ClockSyncConfig.PROBES_PER_BURST) {
                    val (best, bestT4) = burstReplies.minByOrNull { (m, replyT4) ->
                        ClockSync.rttMicros(m.clientTime, m.serverReceive, m.serverSend, replyT4)
                    }!!
                    burstReplies.clear()
                    clockSync.processMeasurement(best.clientTime, best.serverReceive, best.serverSend, bestT4)
                }
                if (!firstMeasurementCompleted) {
                    firstMeasurementCompleted = true
                    // Drop chunks that arrived with offset=0, before the first estimate was ready:
                    // their scheduled times were computed against a meaningless clock.
                    audioBuffer.flush()
                    audioScope.launch { audioPlayer.flushSink() }
                }
                if (_state.value == ClientState.CLOCK_SYNCING) {
                    _state.value = ClientState.STREAMING
                }
            }
            is StreamStart -> {
                if (msg.player != null) _streamFormat.value = msg.player
                if (msg.artwork != null) _streamArtwork.value = msg.artwork
                if (msg.visualizer != null) _visualizerStreamConfig.value = msg.visualizer
                val artworkChannels = msg.artwork?.channels ?: emptyList()
                Timber.d("SendSpinClient: stream/start artwork channels=%d", artworkChannels.size)
                artworkChannels.forEachIndexed { i, ch ->
                    Timber.d("SendSpinClient: stream/start artwork ch=%d source=%s %dx%d", i, ch.source, ch.width, ch.height)
                }
                if (msg.player != null) {
                    Timber.i("SendSpinClient: stream/start codec=%s", msg.player.codec)
                    sendClientState()
                    // The buffer clear MUST be synchronous here on the reader thread so it lands in
                    // wire order — strictly after the last old-stream chunk (offered earlier on this
                    // same thread) and before the first new-stream chunk (offered later). OkHttp
                    // delivers onMessage on one thread, so that ordering holds by construction. An
                    // async hop would let a straggler old chunk slip in after the clear and mix the
                    // two streams together.
                    audioBuffer.flush()
                    audioScope.launch {
                        // The sink drain is async (audio thread) and does NOT clear the buffer, so
                        // it cannot wipe new-stream chunks already offered after the clear above.
                        audioPlayer.flushSink()
                        pendingStopJob?.cancel()
                        pendingStopJob = null
                        if (audioPlayer.isPlaying) {
                            audioPlayer.transition(msg.player)
                        } else {
                            audioPlayer.configure(msg.player)
                            audioPlayer.start()
                        }
                    }
                } else {
                    Timber.d("SendSpinClient: stream/start (artwork-only, no audio)")
                }
            }
            is StreamClear -> {
                val roles = msg.roles
                val clearPlayer = roles == null || roles.any { it == "player@v1" || it == "player" }
                Timber.d("SendSpinClient: stream/clear roles=%s", roles)
                if (clearPlayer) {
                    // Same split as the discontinuity stream/start: synchronous buffer clear
                    // (ordered on the reader thread), async sink drain.
                    audioBuffer.flush()
                    audioScope.launch { audioPlayer.flushSink() }
                }
            }
            is StreamEnd -> {
                val roles = msg.roles
                val endPlayer = roles == null || roles.any { it == "player@v1" || it == "player" }
                val endArtwork = roles == null || roles.any { it == "artwork@v1" || it == "artwork" }
                Timber.d("SendSpinClient: stream/end roles=%s", roles)
                if (endPlayer) {
                    _streamFormat.value = null
                    sendClientState()
                    audioScope.launch {
                        pendingStopJob?.cancel()
                        // Don't flush here — transition() and configure() flush when the next
                        // stream/start arrives. Flushing now empties the buffer immediately,
                        // causing underrun warnings during track handoffs.
                        pendingStopJob = audioScope.launch {
                            delay(SEEK_HANDOFF_MS)
                            pendingStopJob = null
                            audioPlayer.stop()
                        }
                    }
                }
                if (endArtwork) {
                    _streamArtwork.value = null
                    _albumArtwork.value = null
                    _artistArtwork.value = null
                }
                val endColor = roles == null || roles.any { it == "color@v1" || it == "color" }
                if (endColor) _colorState.value = null
                val endVisualizer = roles == null || roles.any { it == "visualizer@v1" || it == "visualizer" }
                if (endVisualizer) _visualizerStreamConfig.value = null
            }
            is GroupUpdate -> {
                // group/update no longer carries volume/muted (spec PR #113/#115 moved those
                // fully into the controller role's server/state object); this message is now
                // playback_state-only as far as this client consumes it.
                Timber.d("SendSpinClient: group/update state=%s", msg.typedPlaybackState)
                msg.typedPlaybackState?.let { _groupPlaybackState.value = it }
            }
            is ServerCommand -> {
                val player = msg.player ?: return
                Timber.d("SendSpinClient: server/command player command=%s volume=%s mute=%s static_delay_ms=%s",
                    player.command, player.volume, player.mute, player.staticDelayMs)
                when (player.command) {
                    "volume" -> {
                        val requested = player.volume
                        if (requested == null) {
                            Timber.w("SendSpinClient: server/command volume missing 'volume' field, ignoring")
                        } else {
                            val clamped = requested.coerceIn(0, 100)
                            if (clamped != requested) {
                                Timber.w("SendSpinClient: server/command volume=%d out of range, clamped to %d", requested, clamped)
                            }
                            playerVolume = clamped
                            settingsStore.putInt(ClientSettingsKeys.PLAYER_VOLUME, playerVolume)
                            applyVolumeToPlayer()
                            sendClientState()
                        }
                    }
                    "mute" -> {
                        val requested = player.mute
                        if (requested == null) {
                            Timber.w("SendSpinClient: server/command mute missing 'mute' field, ignoring")
                        } else {
                            playerMuted = requested
                            settingsStore.putInt(ClientSettingsKeys.PLAYER_MUTED, if (playerMuted) 1 else 0)
                            applyVolumeToPlayer()
                            sendClientState()
                        }
                    }
                    "set_static_delay" -> {
                        val ms = player.staticDelayMs
                        if (ms == null) {
                            Timber.w("SendSpinClient: server/command set_static_delay missing 'static_delay_ms' field, ignoring")
                        } else {
                            setStaticDelayMs(ms)
                        }
                    }
                    else -> Timber.d("SendSpinClient: unhandled server/command player.command=%s", player.command)
                }
            }
            is UnknownMessage -> Timber.d("SendSpinClient: unknown message type '%s'", msg.type)
            null -> { /* parse error already logged by MessageParser */ }
            else -> { /* sealed when — exhaustive */ }
        }
    }

    /**
     * Applies this player's own volume/mute (set via server/command) to the audio output,
     * converting the perceived-loudness value (0-100) to a linear gain via (vol/100)^1.5.
     * Defaults to full volume until the server sends an explicit player volume command.
     */
    private fun applyVolumeToPlayer() {
        val gain = if (playerMuted) 0f else (playerVolume / 100.0).pow(1.5).toFloat()
        audioPlayer.setVolume(gain)
    }

    @Suppress("DEPRECATION")
    private fun mergeControllerWithMetadata(msg: ServerState): ControllerState? {
        val ctrl = msg.controller
        val rawRepeat  = msg.metadata?.repeat  ?: JsonOptional.Absent
        val rawShuffle = msg.metadata?.shuffle ?: JsonOptional.Absent
        return when {
            ctrl != null -> {
                // Priority: controller (if Present) > metadata (if Present) > existing stored value.
                // Absent from both sources means the server did not touch the field — preserve it.
                val stored  = _controllerState.value
                val repeat  = when {
                    ctrl.repeat  is JsonOptional.Present -> ctrl.repeat
                    rawRepeat    is JsonOptional.Present -> rawRepeat
                    else -> stored?.repeat ?: JsonOptional.Absent
                }
                val shuffle = when {
                    ctrl.shuffle is JsonOptional.Present -> ctrl.shuffle
                    rawShuffle   is JsonOptional.Present -> rawShuffle
                    else -> stored?.shuffle ?: JsonOptional.Absent
                }
                ctrl.copy(repeat = repeat, shuffle = shuffle)
            }
            rawRepeat is JsonOptional.Present || rawShuffle is JsonOptional.Present -> {
                // No controller object. Old server sends repeat/shuffle only via metadata.
                // Only update an existing controller state to avoid inventing volume/muted defaults.
                val current = _controllerState.value ?: return null
                current.copy(
                    repeat  = if (rawRepeat  is JsonOptional.Present) rawRepeat  else current.repeat,
                    shuffle = if (rawShuffle is JsonOptional.Present) rawShuffle else current.shuffle,
                )
            }
            else -> null
        }
    }

    /**
     * Optional hook called for every received audio chunk before it enters [audioBuffer].
     * Receives all chunks regardless of clock sync state, making it suitable for recording
     * or conformance testing where buffer-drop logic should not apply.
     */
    var onAudioChunk: ((AudioChunk) -> Unit)? = null

    /**
     * Optional hook called for every received visualizer frame. The frame carries a server-clock
     * timestamp; use [toLocalMicros] to convert it for display scheduling.
     */
    var onVisualizerFrame: ((VisualizerFrame) -> Unit)? = null

    private var firstAudioChunkLogged = false

    internal fun handleBinaryMessage(bytes: ByteString) {
        when (val msg = parser.parseBinary(bytes)) {
            is MessageParser.BinaryAudio -> {
                onAudioChunk?.invoke(msg.chunk)
                if (!firstAudioChunkLogged) {
                    firstAudioChunkLogged = true
                    Timber.i("SendSpinClient: first audio chunk — ts=%d size=%d bufferSize=%d",
                        msg.chunk.serverTimestampMicros, msg.chunk.data.size, audioBuffer.size)
                }
                audioBuffer.offer(msg.chunk)
            }
            is MessageParser.BinaryVisualizer -> {
                onVisualizerFrame?.invoke(msg.frame)
            }
            is MessageParser.BinaryArtwork -> {
                val data = if (msg.data.isEmpty()) null else msg.data
                Timber.d("SendSpinClient: artwork binary ch=%d size=%d -> %s",
                    msg.channel, msg.data.size, if (data == null) "clear" else "set")
                when (preferences.artworkChannels.getOrNull(msg.channel)?.source) {
                    "album"  -> _albumArtwork.value = data
                    "artist" -> _artistArtwork.value = data
                    else     -> Timber.w("SendSpinClient: artwork binary unknown channel %d", msg.channel)
                }
            }
            null -> {
                Timber.w("SendSpinClient: unparseable binary frame size=%d type=0x%02x",
                    bytes.size, if (bytes.size > 0) bytes[0].toInt() and 0xFF else -1)
            }
        }
    }

    // ── Hello ─────────────────────────────────────────────────────────────────

    /** VISUALIZER needs both signals to agree: the role opt-in and a non-null support block. */
    private fun advertises(role: OptionalRole): Boolean =
        role in preferences.supportedOptionalRoles &&
            (role != OptionalRole.VISUALIZER || preferences.visualizerSupport != null)

    private fun buildClientHello(): ClientHello {
        // Iterate the enum, not the caller's Set, so wire order stays deterministic.
        val roles = OptionalRole.entries.filter { advertises(it) }.map { it.roleName }
        return ClientHello(
            payload = ClientHelloPayload(
                clientId = clientId,
                name = clientName,
                deviceInfo = DeviceInfo(manufacturer, productName, softwareVersion),
                macAddress = macAddress,
                supportedRoles = roles,
                playerSupport = if (advertises(OptionalRole.PLAYER)) {
                    PlayerSupport(
                        supportedFormats = preferences.supportedFormats,
                        bufferCapacity = preferences.playerBufferCapacity,
                        supportedCommands = preferences.playerSupportedCommands,
                    )
                } else null,
                metadataSupport = if (advertises(OptionalRole.METADATA)) MetadataSupport() else null,
                artworkSupport = if (advertises(OptionalRole.ARTWORK)) {
                    ArtworkSupport(channels = preferences.artworkChannels)
                } else null,
                controllerSupport = if (advertises(OptionalRole.CONTROLLER)) ControllerSupport() else null,
                colorSupport = if (advertises(OptionalRole.COLOR)) ColorSupport() else null,
                visualizerSupport = preferences.visualizerSupport.takeIf { advertises(OptionalRole.VISUALIZER) },
            )
        )
    }

    private fun sendHello(socket: SendSpinWebSocket) {
        val json = clientHelloAdapter.toJson(buildClientHello())
        Timber.d("SendSpinClient: >> %s", json)
        socket.send(json)
    }

    // ── ServerHello ───────────────────────────────────────────────────────────

    private fun handleServerHello(msg: ServerHello) {
        serverNameStr = msg.name
        _serverName.value = msg.name
        _serverId.value = msg.serverId
        _serverHello.value = msg
        lastConnectionReason = msg.connectionReason
        Timber.i("SendSpinClient: server hello from '%s' reason=%s", serverNameStr, msg.connectionReason)
        _state.value = ClientState.CLOCK_SYNCING
        applyVolumeToPlayer()
        sendClientState()
        startClockSync()
        startPeriodicStateReports()
        startDiagnosticsUpdates()
    }

    // ── Clock sync ────────────────────────────────────────────────────────────

    private fun startClockSync() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                sendClockBurst()
                delay(ClockSyncConfig.BURST_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendClockBurst() {
        repeat(ClockSyncConfig.PROBES_PER_BURST) {
            val t1 = ClockSync.localMicros()
            ws?.send(clientTimeAdapter.toJson(ClientTime(payload = ClientTimePayload(clientTime = t1))))
            delay(ClockSyncConfig.PROBE_INTERVAL_MS)
        }
    }

    // ── Periodic client/state ─────────────────────────────────────────────────

    private fun sendClientState() {
        val activeRoles = _serverHello.value?.activeRoles
        // activeRoles is null before the server hello, when the volume/mute/delay setters can
        // already report state — so gate on our own advertisement too.
        val playerActive = advertises(OptionalRole.PLAYER) &&
            (activeRoles == null || activeRoles.any { it == "player@v1" || it == "player" })
        val player = if (playerActive) {
            PlayerStatePayload(
                volume = playerVolume,
                muted = playerMuted,
                staticDelayMs = staticDelayMs,
                requiredLeadTimeMs = requiredLeadTimeMs,
                minBufferMs = minBufferMs,
            )
        } else {
            null
        }
        ws?.send(clientStateMsgAdapter.toJson(ClientStateMsg(payload = ClientStateMsgPayload(player = player))))
    }

    private fun startPeriodicStateReports() {
        stateJob?.cancel()
        stateJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                sendClientState()
            }
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun startDiagnosticsUpdates() {
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            while (isActive) {
                delay(500L)
                _diagnostics.value = DiagnosticsSnapshot(
                    state = _state.value,
                    serverName = serverNameStr,
                    clockOffsetMs = clockSync.lastOffsetMicros / 1000.0,
                    clockDriftPpm = clockSync.lastDriftPpm,
                    lastRttMicros = clockSync.lastRttMicros,
                    bufferSize = audioBuffer.size,
                    droppedChunks = audioBuffer.droppedChunks,
                    lateChunks = audioBuffer.lateChunks,
                    droppedDecodeFrames = audioPlayer.droppedDecodeFrames,
                    isAudioPlaying = audioPlayer.isPlaying,
                )
            }
        }
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private fun scheduleReconnect(immediate: Boolean) {
        scope.launch {
            if (immediate) {
                Timber.i("SendSpinClient: reconnecting immediately")
                reconnectAttempt = 0
            } else {
                val backoffMs = (RECONNECT_BASE_MS * (1L shl reconnectAttempt.coerceAtMost(6))).coerceAtMost(RECONNECT_MAX_MS)
                Timber.i("SendSpinClient: reconnecting in %d ms (attempt %d)", backoffMs, reconnectAttempt + 1)
                delay(backoffMs)
                reconnectAttempt++
            }
            doConnect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun clearSessionState() {
        _serverName.value = ""
        _serverId.value = ""
        _serverHello.value = null
        serverNameStr = ""
        lastConnectionReason = null
        _groupPlaybackState.value = null
        _controllerState.value = null
        _colorState.value = null
        _visualizerStreamConfig.value = null
        _streamFormat.value = null
        _streamArtwork.value = null
        _albumArtwork.value = null
        _artistArtwork.value = null
        firstAudioChunkLogged = false
        firstMeasurementCompleted = false
        clockSync.reset()   // drop stale Kalman state so the next session re-seeds from its own server clock
        burstReplies.clear()
    }

    private fun cleanupJobs() {
        clockJob?.cancel(); clockJob = null
        stateJob?.cancel(); stateJob = null
        diagnosticsJob?.cancel(); diagnosticsJob = null
        _groupPlaybackState.value = null
        _controllerState.value = null
        _streamFormat.value = null
        audioScope.launch {
            pendingStopJob?.cancel()
            pendingStopJob = null
            audioPlayer.stop()
        }
    }

    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS  = 30_000L
        // Natural track-to-track transitions: aiosendspin sends stream/end then stream/start
        // for the next song. If stream/start arrives within this window, the pending stop is
        // cancelled and transition() handles the handoff gaplessly.
        // Seeks/jumps now use stream/clear instead (aiosendspin PR #237) and no longer go
        // through this path. Can be removed once aiosendspin adopts spec-compliant track
        // transitions that don't emit stream/end (see CLAUDE.md "Future simplifications").
        private const val SEEK_HANDOFF_MS   = 500L
    }
}
