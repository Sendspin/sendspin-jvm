package com.sendspin.protocol

import kotlinx.coroutines.flow.Flow
import com.sendspin.protocol.ProtocolLog as Timber

/**
 * Advertises this client over mDNS so that SendSpin servers can discover and connect to it.
 *
 * Service type: `_sendspin._tcp.`
 * TXT attributes: `path=/sendspin`, `manufacturer`, `model`
 *
 * The returned flow is cold — advertisement starts on collection and is unregistered on cancel.
 * Emits [Unit] once when registration succeeds.
 *
 * The actual mDNS implementation is provided by the [NsdRegistrar] argument (e.g.
 * [com.sendspin.tv.network.AndroidNsdRegistrar] on Android, a no-op stub in tests/CLI).
 */
class ClientAdvertiser(private val registrar: NsdRegistrar) {

    fun advertise(
        port: Int,
        name: String,
        manufacturer: String = "",
        model: String = "",
    ): Flow<Unit> {
        Timber.i("ClientAdvertiser: registering name='%s' type='%s' port=%d", name, SERVICE_TYPE, port)
        return registrar.register(
            serviceName = name,
            serviceType = SERVICE_TYPE,
            port = port,
            attributes = mapOf(
                "path" to "/sendspin",
                "manufacturer" to manufacturer,
                "model" to model,
            ),
        )
    }

    companion object {
        const val SERVICE_TYPE = "_sendspin._tcp."
    }
}
