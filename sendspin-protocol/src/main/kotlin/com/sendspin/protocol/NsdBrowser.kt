package com.sendspin.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Platform-independent abstraction for mDNS service browsing.
 *
 * The Android implementation ([com.sendspin.tv.network.AndroidNsdBrowser]) wraps
 * `NsdManager`. A no-op implementation can be supplied where mDNS is not needed.
 */
interface NsdBrowser {
    /**
     * Starts browsing for services of the given type. Returns a cold [Flow] that emits
     * [NsdServiceEvent]s until the flow is cancelled or a [NsdServiceEvent.BrowseError] closes it.
     */
    fun browse(serviceType: String): Flow<NsdServiceEvent>
}

sealed interface NsdServiceEvent {
    /** A service of the browsed type was found on the network (not yet resolved). */
    data class ServiceFound(val name: String) : NsdServiceEvent
    /**
     * A previously-found service was resolved to a host/port.
     *
     * [name] is a discovery-time hint only (spec `## Establishing a Connection`): once connected,
     * the `client/hello`/`server/hello` name is authoritative and may differ. Callers should not
     * keep displaying this name past the point where a hello has been exchanged — see
     * [SendSpinClient.serverName].
     */
    data class ServiceResolved(val name: String, val host: String, val port: Int) : NsdServiceEvent
    /** A previously-found service is no longer available. */
    data class ServiceLost(val name: String) : NsdServiceEvent
    /** The browse operation failed with the given platform error code. */
    data class BrowseError(val code: Int) : NsdServiceEvent
}
