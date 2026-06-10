package com.sendspin.protocol

/**
 * Host-provided persistence for client settings that must survive process restarts
 * (e.g. `static_delay_ms`, last-played server id). The protocol module has no
 * filesystem/platform assumptions, so the host app supplies an implementation
 * backed by its own preferences API, a file, etc.
 */
interface ClientSettingsStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
}

/** Default no-op store: returns defaults and discards writes. */
object NoOpClientSettingsStore : ClientSettingsStore {
    override fun getInt(key: String, default: Int): Int = default
    override fun putInt(key: String, value: Int) {}
    override fun getString(key: String, default: String?): String? = default
    override fun putString(key: String, value: String) {}
}

/** Setting keys used by [SendSpinClient]. */
object ClientSettingsKeys {
    const val STATIC_DELAY_MS = "static_delay_ms"
}
