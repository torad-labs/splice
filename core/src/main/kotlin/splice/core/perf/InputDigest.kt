// NEW: V4-446 — one canonical SHA-256 spelling for persisted code-mode input and preflight prefixes.
package splice.core.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

private const val PREFIX_INPUT_FIELD = "input"

/** The same lowercase SHA-256/UTF-8 hex code-mode records have persisted since their creation. */
public object InputDigest {
    public fun hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    public fun capture(request: JsonObject): InputPrefix? {
        val input = request[PREFIX_INPUT_FIELD] as? JsonArray ?: return null
        val properties = JsonObject(request.filterKeys { it != PREFIX_INPUT_FIELD }).toString()
        return InputPrefix(
            count = input.size,
            inputDigest = hex(input.toString()),
            propertiesDigest = hex(properties),
            requestBytes = request.toString().toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }
}

/** No prompt bytes retained. Measured input anchors future growth only when the measured input
 * items are a byte-identical prefix and all non-input request properties still agree. */
public data class InputPrefix(
    val count: Int,
    val inputDigest: String,
    val propertiesDigest: String,
    val requestBytes: Long,
) {
    public fun extendedBy(request: JsonObject): Boolean {
        val input = request[PREFIX_INPUT_FIELD] as? JsonArray ?: return false
        if (input.size < count) return false
        val prefix = JsonArray(input.take(count)).toString()
        val properties = JsonObject(request.filterKeys { it != PREFIX_INPUT_FIELD }).toString()
        return InputDigest.hex(prefix) == inputDigest && InputDigest.hex(properties) == propertiesDigest
    }
}
