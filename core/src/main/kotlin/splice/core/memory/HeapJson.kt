// NEW: heap weights for already-materialized JSON owners, independent of their wire encoding.
package splice.core.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// why: reserve conservative object and map-entry overhead in addition to each UTF-16 payload.
private const val JSON_OWNER_BYTES = 64L

// why: array elements retain a reference and allocation slack separately from their child graph.
private const val JSON_REFERENCE_BYTES = 16L

/** Conservative object, map-entry, reference and UTF-16 payload weights, not JVM live-byte measurements. */
public object HeapJson {
    public fun bytes(value: JsonElement): Long = when (value) {
        is JsonPrimitive -> text(value.content)
        is JsonArray -> value.fold(JSON_OWNER_BYTES) { total, child ->
            add(total, add(JSON_REFERENCE_BYTES, bytes(child)))
        }
        is JsonObject -> value.entries.fold(JSON_OWNER_BYTES) { total, entry ->
            add(total, add(JSON_OWNER_BYTES, add(text(entry.key), bytes(entry.value))))
        }
    }

    public fun text(value: CharSequence): Long = JSON_OWNER_BYTES + value.length * 2L

    public fun add(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
