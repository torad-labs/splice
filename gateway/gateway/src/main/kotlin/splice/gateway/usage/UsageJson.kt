// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: alias-normalized usage parsing (Anthropic
// names, OpenAI Responses names, cached-token detail) moved verbatim onto its own collaborator
// (HD-24, 2026-08-17). num/firstNum/from are byte-identical; only the file changed.
package splice.gateway.usage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

// output_tokens is the one usage field name written from several sites; naming it once keeps the
// wire contract single-sourced (the others stay inline — they don't repeat enough to warrant it).
// Widened from private to internal (HD-24): UsageRing and UsageHud, in sibling files, both read it.
internal const val OUTPUT_TOKENS = "output_tokens"

/** Usage-JSON scalar reading, shared by [TurnUsage] construction, the HUD payload and the store.
 *  A collaborator rather than file-level helpers: a Kotlin `private` member is CLASS-private, and
 *  three types here need the same reader — a second copy is exactly what CX-18 forbade. */
public class UsageJson {
    internal fun num(el: JsonElement?): Long? =
        (el as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    /** First key whose value parses as a number. CX-18: this chain moved to :core (JsonScalars
     *  firstLong) so the dialects, the Responses harvest and this payload builder share ONE
     *  definition; the local NAME is kept (HD-20 moved the receiver to the first parameter, the
     *  JsonScalars shape this thin wrapper already delegates to). */
    private fun firstNum(obj: JsonObject, vararg keys: String): Long? = JsonScalars.firstLong(obj, *keys)

    /** Parse a raw usage object into the alias-normalized [TurnUsage]. */
    public fun from(usage: JsonObject?): TurnUsage {
        val u = usage ?: JsonObject(emptyMap())
        val cachedDetail = (u["input_tokens_details"] as? JsonObject)?.let { num(it["cached_tokens"]) }
        return TurnUsage(
            inputTokens = firstNum(u, "input_tokens", "prompt_tokens") ?: 0,
            outputTokens = firstNum(u, OUTPUT_TOKENS, "completion_tokens") ?: 0,
            cacheCreationInputTokens = num(u["cache_creation_input_tokens"]) ?: 0,
            cacheReadInputTokens = num(u["cache_read_input_tokens"]) ?: cachedDetail ?: 0,
        )
    }
}

/** Usage aliases: Anthropic names + OpenAI Responses names + cached-token detail. */
public data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
)
