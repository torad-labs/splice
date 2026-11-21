// NEW: two-view parse of an incoming Anthropic body — the typed request for the pipeline
// plus the raw JsonObject for loose-field reads (the effort-precedence chain reads
// body.effort / body.reasoning_effort / body.output_config.effort / body.metadata.effort /
// body.reasoning.effort — fields Anthropic never standardized; keeping the raw view beats
// widening the typed schema for every alias).
package splice.core.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.core.wire.AnthropicRequest

public val lenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

public data class AnthropicTurnBody(
    val raw: JsonObject,
    val typed: AnthropicRequest,
    /** The Claude Code subagent that sent the request (its x-claude-code-agent-id header), set by the head that
     *  received it; null on the main thread and wherever no head read one. */
    val agentId: String? = null,
) {
    /** The session the body names: `metadata.user_id` is itself a JSON string holding `device_id`,
     *  `account_uuid` and `session_id`. Null when the body carries none or the string is not that. */
    val sessionId: String?
        get() {
            val text = JsonScalars.str((raw["metadata"] as? JsonObject)?.get("user_id")) ?: return null
            val ids = JsonScalars.objectOrNull(lenientJson, text)
            return JsonScalars.str(ids, "session_id")?.takeIf(String::isNotBlank)
        }

    /** custom-provider (cp-output): this body asking for at most [cap] output tokens: the smaller of what it asked and
     *  [cap], or [cap] when it named none; itself when [cap] is null or it already asked no more. Both views move
     *  together, so every dialect's assembler writes the capped number in its own field. */
    public fun withOutputCap(cap: Long?): AnthropicTurnBody {
        if (cap == null) return this
        val asked = typed.maxTokens?.takeIf { it > 0 }
        if (asked != null && asked <= cap) return this
        return copy(raw = JsonObject(raw + (MAX_TOKENS_KEY to JsonPrimitive(cap))), typed = typed.copy(maxTokens = cap))
    }
}

private const val MAX_TOKENS_KEY = "max_tokens"

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
