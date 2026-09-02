// NEW: two-view parse of an incoming Anthropic body — the typed request for the pipeline
// plus the raw JsonObject for loose-field reads (the effort-precedence chain reads
// body.effort / body.reasoning_effort / body.output_config.effort / body.metadata.effort /
// body.reasoning.effort — fields Anthropic never standardized; keeping the raw view beats
// widening the typed schema for every alias).
package splice.core.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.wire.AnthropicRequest

public val lenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

public data class AnthropicTurnBody(
    val raw: JsonObject,
    val typed: AnthropicRequest,
)

/** The two-view parse, as a named object since the 2026-08-16 style migration (HD-M8): it is a
 *  FACTORY for [AnthropicTurnBody] over a raw request string, so it cannot be a member of the type
 *  it builds. Same name, same lenient config, same parsing semantics — the dialects' exact contract. */
public object AnthropicParse {

    public fun parseAnthropicBody(text: String): AnthropicTurnBody {
        val raw = lenientJson.parseToJsonElement(text).jsonObject
        val typed = lenientJson.decodeFromJsonElement(AnthropicRequest.serializer(), raw)
        return AnthropicTurnBody(raw = raw, typed = typed)
    }
}
