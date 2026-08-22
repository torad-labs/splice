// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the ONE tool_search_call
// parser (the v29 copies-drift law) — the only member of the old file with a real cross-file
// production caller (Harvested.kt's harvestToolSearchCalls).
package splice.dialect.responses

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.util.JsonScalars

private const val TOOL_SEARCH_EXECUTION_CLIENT = "client"

/** The ONE tool_search_call parser — shared by the live event path (ResponsesItemFold) and the
 *  terminal-object harvest (Harvested.kt), never two hand-rolled readers of the same shape. */
internal class ResponsesToolSearchParse {

    private val frames = ResponsesFrameParse()

    /** Parses one tool_search_call item into a [ToolSearchCall], or null when it should be skipped
     *  (server-executed — execution:"server" means the backend already answered, protocol/src/
     *  models.rs:3693-3728 — or carries no call_id). */
    fun parseToolSearchCall(item: JsonObject): ToolSearchCall? {
        val execution = JsonScalars.strOrEmpty(item["execution"])
        if (execution.isNotEmpty() && execution != TOOL_SEARCH_EXECUTION_CLIENT) return null
        // NB: no `id` fallback here (unlike function_call's onItemAdded) — call.raw is echoed
        // VERBATIM into the continuation, so a synthesized id would produce a tool_search_output
        // keyed by a value the echoed call itself doesn't carry: an unpairable item the backend
        // 400s (review 2026-07-24). Skipping is the spec behavior and matches codex-rs's own
        // catch-all (a client-execution call always carries call_id; this is defensive-only).
        val callId = JsonScalars.strOrEmpty(item["call_id"])
        if (callId.isEmpty()) return null
        val (query, limit) = parseToolSearchArguments(item["arguments"])
        return ToolSearchCall(callId = ToolSearchCallId(callId), query = query, limit = limit, raw = item)
    }

    /** `arguments` is a real JSON object on this item type (unlike function_call, where it is a
     *  string) — a string is accepted defensively and parsed. Unparseable/absent arguments yield
     *  query="" (answered exhaustively downstream) rather than dropping the call. */
    private fun parseToolSearchArguments(arguments: JsonElement?): Pair<String, Int?> {
        val obj = when (arguments) {
            is JsonObject -> arguments
            is JsonPrimitive -> parseArgumentsString(JsonScalars.strOrEmpty(arguments))
            else -> null
        } ?: return "" to null
        return JsonScalars.strOrEmpty(obj["query"]) to frames.intOr(obj["limit"])
    }

    private fun parseArgumentsString(text: String): JsonObject? {
        if (text.isEmpty()) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (ignored: SerializationException) {
            // malformed tool_search_call arguments: fall through to query="" (answered exhaustively
            // downstream), matching driveTurn's own precedent for malformed upstream frames.
            null
        }
    }
}
