package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/**
 * Valid source cache breakpoints are optional optimization hints. Responses uses its own automatic
 * caching policy; accepting these hints does not promise the source breakpoint or its requested TTL.
 * No Anthropic cache field is copied to the Responses wire.
 */
internal object ResponsesCacheHints {
    private val sourceLifetimes = setOf("5m", "1h")

    fun accepts(hint: JsonElement?): Boolean = when (hint) {
        null, JsonNull -> true
        is JsonObject -> JsonScalars.str(hint, "type") == "ephemeral" &&
            (hint[SOURCE_CACHE_TTL] == null || JsonScalars.str(hint, SOURCE_CACHE_TTL) in sourceLifetimes)
        is JsonArray, is JsonPrimitive -> false
    }
}

private const val SOURCE_CACHE_TTL = "ttl"
