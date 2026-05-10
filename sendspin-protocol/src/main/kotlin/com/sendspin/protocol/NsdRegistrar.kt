package com.sendspin.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Platform-independent abstraction for mDNS service registration.
 *
 * The Android implementation ([com.sendspin.tv.network.AndroidNsdRegistrar]) wraps
 * `NsdManager`. A no-op implementation can be supplied where mDNS is not needed
 * (e.g. the conformance CLI, where ports are passed as command-line arguments).
 */
interface NsdRegistrar {
    /**
     * Registers a service with the given parameters. Returns a cold [Flow] that emits [Unit]
     * once when registration succeeds and stays open until the flow is cancelled (at which
     * point the service is unregistered).
     */
    fun register(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
    ): Flow<Unit>
}
