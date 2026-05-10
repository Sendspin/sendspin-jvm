package com.sendspin.protocol

import java.util.logging.Level
import java.util.logging.Logger

/**
 * JVM-compatible logging facade with a Timber-compatible API.
 *
 * Backed by `java.util.logging` (built-in on both JVM and Android).
 * Protocol source files import this as `import com.sendspin.protocol.ProtocolLog as Timber`
 * so call sites are unchanged.
 */
internal object ProtocolLog {
    private val log = Logger.getLogger("sendspin-protocol")

    private fun fmt(message: String, args: Array<out Any?>): String =
        if (args.isEmpty()) message
        else try { message.format(*args) } catch (e: Exception) { "$message [log format error: $e]" }

    fun v(message: String, vararg args: Any?) = log.finest(fmt(message, args))
    fun d(message: String, vararg args: Any?) = log.fine(fmt(message, args))
    fun i(message: String, vararg args: Any?) = log.info(fmt(message, args))
    fun w(message: String, vararg args: Any?) = log.warning(fmt(message, args))
    fun w(t: Throwable?, message: String, vararg args: Any?) =
        log.log(Level.WARNING, fmt(message, args), t)
    fun e(message: String, vararg args: Any?) = log.severe(fmt(message, args))
    fun e(t: Throwable?, message: String, vararg args: Any?) =
        log.log(Level.SEVERE, fmt(message, args), t)
}
