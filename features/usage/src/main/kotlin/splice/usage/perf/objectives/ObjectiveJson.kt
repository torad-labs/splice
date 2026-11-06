package splice.usage.perf.objectives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object ObjectiveJson {
    val codec = Json { encodeDefaults = true }

    fun failure(code: String, status: String = "error"): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("status", status)
        put("error", buildJsonObject { put("code", code) })
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun read(path: Path, limit: Int): String? {
        if (!Files.exists(path)) return null
        val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) throw IOException("objective_state_limit")
        return bytes.toString(Charsets.UTF_8)
    }

    /** Copies before retaining: no returned subtree aliases the engine's retained state or a query lease. */
    fun reply(value: JsonObject): JsonObject {
        val encoded = value.toString()
        val lease = PerfWorkBudget.acquire(encoded.length.toLong() * OBJECTIVE_JSON_CHARGE + OVERHEAD)
            ?: return failure("shared_work_budget", "busy")
        return try {
            val result = codec.parseToJsonElement(encoded) as JsonObject
            lease.retain(result, encoded.length.toLong() * OBJECTIVE_JSON_CHARGE + OVERHEAD)
            result
        } catch (failure: IllegalArgumentException) {
            lease.close()
            throw failure
        }
    }

    fun number(value: Number?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    fun obj(value: JsonObject, vararg path: String): JsonObject = path.fold(value) { current, key ->
        current[key] as? JsonObject ?: JsonObject(emptyMap())
    }
    fun text(value: JsonObject, key: String): String? = JsonScalars.str(value, key)
    fun number(value: JsonObject, key: String): Double? =
        (value[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
    fun long(value: JsonObject, key: String): Long? = (value[key] as? JsonPrimitive)?.longOrNull
}

// Charge eight bytes per encoded character for the detached decoded objective tree and its copied strings.
private const val OBJECTIVE_JSON_CHARGE = 8L

// Add 4 KiB for objective response collections and lease ownership beyond the text-dependent charge.
private const val OVERHEAD = 4_096L
