// NEW: head-catalog (docs/specs/door-default.md). Claude Code as a client of an LLM gateway, its model discovery
// (code.claude.com/docs/en/llm-gateway-protocol.md, "Model discovery", read Nov 19, 2026): with
// CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY set and no provider switch, Claude Code asks `GET <base>/v1/models?limit=1000`
// at startup, waits 3 s for it, treats a redirect as a failure, and fills `/model` from each row's `id`, `display_name`
// and `description`. It keeps only a row whose `id` contains `claude` or `anthropic`, in any case. Facts of Claude
// Code's dialect, so they live here with the others; the gateway's answer is the app's (GatewayModels).
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One model a gateway lists: the id a turn names it by, its name in `/model`, and the line under it. */
public data class GatewayModel(val id: String, val displayName: String, val description: String)

public class GatewayDiscovery {
    /** Whether Claude Code keeps a discovered [id]: it contains `claude` or `anthropic`, in any case. */
    public fun keeps(id: String): Boolean = KEPT.containsMatchIn(id)

    /** The id [selector] is listed by: itself where Claude Code keeps it, else with `claude-` before it, which the
     *  gateway reads back as [selector] ([selectorOf]). */
    public fun idOf(selector: String): String = if (keeps(selector)) selector else "$LISTED_PREFIX$selector"

    /** The selector an id of [idOf]'s `claude-` spelling stands for, or null when [id] is not one: what follows
     *  `claude-` is a selector Claude Code would not have kept by itself. */
    public fun selectorOf(id: String): String? =
        id.takeIf { it.startsWith(LISTED_PREFIX) }?.removePrefix(LISTED_PREFIX)
            ?.takeUnless { it.isEmpty() || keeps(it) }

    /** The line `/model` shows under a row of [command]'s head. */
    public fun description(command: String): String = "splice · $command"

    /** One page of [models], in Anthropic's own /v1/models shape: at most [limit] rows (1 to [GATEWAY_MAX_LIMIT]),
     *  after the row whose id is [afterId] when it names one. */
    public fun page(models: List<GatewayModel>, limit: Int?, afterId: String?): JsonObject {
        val from = afterId?.let { id -> models.indexOfFirst { it.id == id } + 1 } ?: 0
        val size = (limit ?: GATEWAY_MAX_LIMIT).coerceIn(1, GATEWAY_MAX_LIMIT)
        val rows = models.drop(from).take(size)
        return buildJsonObject {
            put("data", JsonArray(rows.map(::row)))
            put("has_more", from + rows.size < models.size)
            put("first_id", rows.firstOrNull()?.let { JsonPrimitive(it.id) } ?: JsonNull)
            put("last_id", rows.lastOrNull()?.let { JsonPrimitive(it.id) } ?: JsonNull)
        }
    }

    private fun row(model: GatewayModel): JsonObject = buildJsonObject {
        put("type", "model")
        put("id", model.id)
        put("display_name", model.displayName)
        put("description", model.description)
        put("created_at", CREATED_AT)
    }
}

/** The most rows one page holds, and what Claude Code's discovery asks for. */
public const val GATEWAY_MAX_LIMIT: Int = 1_000

// What Claude Code keeps of a discovery answer (since 2.1.223: anywhere in the id, any case).
private val KEPT = Regex("claude|anthropic", RegexOption.IGNORE_CASE)

// What a selector Claude Code would drop is listed with, so it keeps it.
private const val LISTED_PREFIX = "claude-"

// Every row's `created_at`: Anthropic's shape asks for an RFC 3339 time, and Claude Code reads none of it.
private const val CREATED_AT = "2026-11-19T00:00:00Z"
