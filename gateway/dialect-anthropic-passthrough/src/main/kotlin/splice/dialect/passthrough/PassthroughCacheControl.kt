// NEW: the deep cache_control stripper — split out of PassthroughRequestBuilder.kt (2026-08-17,
// concentration campaign). The single most shared primitive in the file, called by five other
// collaborators; its own doc comment already noted it duplicates MfjsSanitizer's depth-capped
// recursion. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PassthroughCacheControl(private val enabled: Boolean) {

    /** Recursively remove every `cache_control` key; other structure passes verbatim. Bounded by
     *  [DEPTH_CAP] (mirrors MfjsSanitizer's guard): client-supplied JSON deeper than the cap is
     *  passed through AS-IS beyond that point — cache_control stripping at extreme depth is
     *  immaterial, and this must never StackOverflow on adversarially nested input. */
    fun stripCacheControl(element: JsonElement, depth: Int = 0): JsonElement {
        if (!enabled) return element
        if (depth >= DEPTH_CAP) return element
        return when (element) {
            is JsonObject -> buildJsonObject {
                for ((key, value) in element) {
                    if (key != CACHE_CONTROL) put(key, stripCacheControl(value, depth + 1))
                }
            }
            is JsonArray -> buildJsonArray { element.forEach { add(stripCacheControl(it, depth + 1)) } }
            else -> element
        }
    }
}

// stripCacheControl's recursion guard (WIRE-1) — far above any legitimate request's
// nesting, well below a stack-overflow depth.
private const val DEPTH_CAP = 200
