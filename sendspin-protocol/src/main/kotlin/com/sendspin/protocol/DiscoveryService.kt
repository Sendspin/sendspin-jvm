package com.sendspin.protocol

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import com.sendspin.protocol.ProtocolLog as Timber

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
) {
    val wsUrl: String get() = "ws://$host:$port/sendspin"
    override fun toString(): String = "$name ($host:$port)"
}

/**
 * Browses for SendSpin servers on the local network using mDNS.
 *
 * Emits the current list of live servers whenever one is found or lost.
 * The flow is cold — discovery starts on collection and stops when canceled.
 *
 * Due to known mDNS reliability issues on some OEM ROMs, up to [MAX_RETRIES] restart
 * attempts are made before the flow terminates with an error.
 *
 * The actual mDNS implementation is provided by the [NsdBrowser] argument (e.g.
 * [com.sendspin.tv.network.AndroidNsdBrowser] on Android, a no-op stub in tests/CLI).
 */
class DiscoveryService(private val browser: NsdBrowser) {

    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val servers = mutableMapOf<String, DiscoveredServer>()

        fun emitServers() { trySend(servers.values.toList()) }

        // Sequential retry loop: each attempt runs to completion (or BrowseError) before
        // the next starts, preventing overlapping browse sessions and stale-server races.
        for (attempt in 0..MAX_RETRIES) {
            val error = browser.browse(SERVICE_TYPE).firstOrNull { event ->
                when (event) {
                    is NsdServiceEvent.ServiceFound ->
                        { Timber.d("NSD: found '%s'", event.name); false }
                    is NsdServiceEvent.ServiceResolved -> {
                        val server = DiscoveredServer(event.name, event.host, event.port)
                        servers[event.name] = server
                        Timber.d("NSD: resolved %s", server)
                        emitServers()
                        false
                    }
                    is NsdServiceEvent.ServiceLost -> {
                        servers.remove(event.name)
                        Timber.d("NSD: lost '%s'", event.name)
                        emitServers()
                        false
                    }
                    is NsdServiceEvent.BrowseError -> {
                        Timber.e("NSD: browse error (code %d)", event.code)
                        true
                    }
                }
            }

            if (error == null) break  // flow completed without error — stop retrying

            if (attempt < MAX_RETRIES) {
                Timber.d("NSD: retrying discovery (%d/%d)", attempt + 1, MAX_RETRIES)
                servers.clear()
                emitServers()
            } else {
                close(IllegalStateException("NSD discovery failed after $MAX_RETRIES retries"))
            }
        }

        awaitClose { }
    }

    companion object {
        private const val SERVICE_TYPE = "_sendspin-server._tcp."
        private const val MAX_RETRIES = 3
    }
}
