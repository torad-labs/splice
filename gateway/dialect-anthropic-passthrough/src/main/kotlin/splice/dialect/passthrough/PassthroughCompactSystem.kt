// NEW: the compaction-directive system-field assembly — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). The one place this dialect INVENTS content rather than
// forwarding it; it is also the only reader of splice.core.turn.CompactInstructions/
// compactDirective, so the import leaves with it. Every relocated member kept its identical name
// and argument list.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.CompactInstructions
import splice.core.turn.compactDirective
import splice.core.util.JsonScalars

internal class PassthroughCompactSystem(private val cache: PassthroughCacheControl) {

    /** CX-02: the scrubbed system field, with the compaction directive appended on a compact turn.
     *
     *  This is the one place passthrough INVENTS content rather than forwarding it, and it is
     *  deliberate: without it a kimi compaction turn is an ordinary tool-stripped turn and a chatty
     *  reply becomes the stored summary. Both legal shapes are handled — Anthropic's `system` is a
     *  string OR an array of blocks — and a compact turn with no system at all still gets one.
     *  Non-compact returns exactly what the old `stripCacheControl` call returned, null included. */
    fun compactAwareSystem(system: JsonElement?, compact: Boolean): JsonElement? {
        val scrubbed = system?.let { cache.stripCacheControl(it) }
        if (!compact) return scrubbed
        val directiveBlock = buildJsonObject {
            put("type", "text")
            put("text", compactDirective)
        }
        return when (scrubbed) {
            is JsonArray -> buildJsonArray {
                scrubbed.forEach { add(it) }
                add(directiveBlock)
            }
            null -> buildJsonArray { add(directiveBlock) }
            // A string system prompt stays a string — appending a block would change its type.
            // strOrEmpty returns "" for any NON-primitive (an object, JSON null), which in a
            // verbatim-forwarding dialect would silently replace a client's unusual-but-forwardable
            // system with the directive alone. Forward it untouched instead and append the
            // directive as its own block, so nothing the client sent is ever dropped.
            is JsonPrimitive -> JsonPrimitive(
                CompactInstructions.withCompactDirective(JsonScalars.strOrEmpty(scrubbed), compact = true),
            )
            else -> buildJsonArray {
                add(scrubbed)
                add(directiveBlock)
            }
        }
    }
}
