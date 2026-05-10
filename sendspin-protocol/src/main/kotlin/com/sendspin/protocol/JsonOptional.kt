package com.sendspin.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Represents three states for a JSON field:
 * - [Absent]       — field was not present in the JSON object
 * - [Present]      — field was present; [Present.value] is null if the JSON value was null
 *
 * Used to distinguish "server sent null (clear this field)" from "server omitted the field
 * (keep previous value)" when accumulating partial metadata updates.
 */
sealed class JsonOptional<out T> {
    object Absent : JsonOptional<Nothing>()
    data class Present<out T>(val value: T?) : JsonOptional<T>()
}

/**
 * Returns the present value (which may be null), or [fallback] if [Absent].
 *
 * Use `orFallback(prev) ?: ""` for non-nullable fields where null means "clear to empty".
 */
fun <T> JsonOptional<T>.orFallback(fallback: T?): T? =
    if (this is JsonOptional.Absent) fallback else (this as JsonOptional.Present).value

/**
 * Moshi adapter factory for [JsonOptional].
 *
 * Register before [KotlinJsonAdapterFactory]:
 * ```
 * Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
 * ```
 *
 * The adapter is only invoked when the field key is present in the JSON object. When the key
 * is absent, Moshi uses the Kotlin default value [JsonOptional.Absent] directly.
 */
class JsonOptionalAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (type !is ParameterizedType) return null
        if (type.rawType != JsonOptional::class.java) return null
        val innerType = type.actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        val inner = moshi.adapter<Any?>(innerType) as JsonAdapter<Any?>
        return object : JsonAdapter<JsonOptional<Any?>>() {
            override fun fromJson(reader: JsonReader): JsonOptional<Any?> =
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Any?>()
                    JsonOptional.Present(null)
                } else {
                    JsonOptional.Present(inner.fromJson(reader))
                }

            override fun toJson(writer: JsonWriter, value: JsonOptional<Any?>?) {
                when (value) {
                    null, JsonOptional.Absent -> writer.nullValue()
                    is JsonOptional.Present -> inner.toJson(writer, value.value)
                }
            }
        }
    }
}
