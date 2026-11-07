package zovo.aiusage.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

// Small typed reads on Gson trees, with the same "typeof" checks the JS version makes.

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray

/** A string value, or null when the key is missing or not a string. */
internal fun JsonObject.str(key: String): String? {
    val v = get(key) as? JsonPrimitive ?: return null
    return if (v.isString) v.asString else null
}

/** A finite number, or null when the key is missing or not a number. */
internal fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive).finite()

internal fun JsonPrimitive?.finite(): Double? {
    if (this == null || !isNumber) return null
    val d = try { asDouble } catch (_: NumberFormatException) { return null }
    return if (d.isFinite()) d else null
}

internal fun JsonObject.isTrue(key: String): Boolean {
    val v = get(key) as? JsonPrimitive ?: return false
    return v.isBoolean && v.asBoolean
}

/** Positive finite number or 0 (JS `num`). */
internal fun num(v: JsonElement?): Double {
    val d = (v as? JsonPrimitive).finite() ?: return 0.0
    return if (d > 0) d else 0.0
}

/** Parses JSON text; null for text that is not JSON. */
internal fun parseJson(text: String): JsonElement? = try {
    JsonParser.parseString(text)
} catch (_: Exception) {
    null
} catch (_: StackOverflowError) {
    null
}

/**
 * Milliseconds for a number (seconds below 1e12) or a date string, like the JS `timeMs`. Null when unreadable.
 * Strings without an offset are read in [zone], as JavaScript's Date.parse does.
 */
internal fun timeMs(v: JsonElement?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val p = v as? JsonPrimitive ?: return null
    if (p.isNumber) {
        val d = p.finite() ?: return null
        return if (d < 1e12) Math.round(d * 1000) else Math.round(d)
    }
    if (p.isString) return parseTime(p.asString, zone)
    return null
}

internal fun parseTime(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (s.isEmpty()) return null
    try { return Instant.parse(s).toEpochMilli() } catch (_: Exception) { }
    try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { }
    try { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { }
    return null
}
