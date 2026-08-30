package com.lifeops.app.connection

/**
 * Typed, read-only accessor over a route's payload. The payload is an untyped
 * `Map<String, Any?>` so it can carry values from any caller (a ViewModel building a call in
 * code, or a future integration decoding JSON), while handlers pull out exactly the fields they
 * need with light coercion and clear errors.
 *
 * `require*` accessors throw [MissingParamException]; the dispatcher turns that into a
 * [ConnectionError.INVALID_PARAMS] failure, so handlers can read required fields without their own
 * validation boilerplate.
 */
class ConnectionParams(private val values: Map<String, Any?>) {

    val keys: Set<String> get() = values.keys

    operator fun get(key: String): Any? = values[key]

    fun has(key: String): Boolean = values.containsKey(key) && values[key] != null

    fun getString(key: String): String? = when (val v = values[key]) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    fun requireString(key: String): String =
        getString(key)?.takeIf { it.isNotBlank() } ?: throw MissingParamException(key)

    fun getInt(key: String): Int? = when (val v = values[key]) {
        null -> null
        is Int -> v
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun getLong(key: String): Long? = when (val v = values[key]) {
        null -> null
        is Long -> v
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    fun getDouble(key: String): Double? = when (val v = values[key]) {
        null -> null
        is Double -> v
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun requireDouble(key: String): Double = getDouble(key) ?: throw MissingParamException(key)

    fun requireInt(key: String): Int = getInt(key) ?: throw MissingParamException(key)

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        getBooleanOrNull(key) ?: default

    /**
     * The tri-state read: null when the caller didn't supply the key at all, which for an optional
     * yes/no answer is a different thing from "no" (see the week-close prompts).
     */
    fun getBooleanOrNull(key: String): Boolean? = when (val v = values[key]) {
        null -> null
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        is Number -> v.toInt() != 0
        else -> null
    }

    /** A list of strings, tolerating either a real list or a single scalar value. */
    fun getStringList(key: String): List<String> = when (val v = values[key]) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { it?.toString() }
        else -> listOf(v.toString())
    }

    companion object {
        val EMPTY = ConnectionParams(emptyMap())

        fun of(vararg pairs: Pair<String, Any?>): ConnectionParams = ConnectionParams(pairs.toMap())
    }
}

/** Thrown by [ConnectionParams.requireString] (and peers) when a required field is missing. */
class MissingParamException(val key: String) :
    IllegalArgumentException("Missing required parameter: $key")
