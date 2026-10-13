// NEW: replay_reasoning=false means reasoning from an earlier turn does not ride back (the operator's
// words in splice.toml: replayed encrypted reasoning thins fresh reasoning). Two paths put it back anyway:
// the gateway cache in front of every client function_call, and code mode's stored continuity. Both end
// in one Responses input array, so this is the one place that drops it, whichever path wrote it.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.websocket.REASONING_ITEM_TYPE

/** Drops reasoning items that precede the last real user prompt; the current turn's reasoning stays. */
public class ResponsesPriorReasoning {
    /** [input] without the reasoning items before the last real prompt; [input] itself when none is dropped
     *  or no prompt exists. A real prompt is a user message whose run of user messages does not start right after
     *  a tool output. Each text beside a tool result rides as its own user message (Claude Code sends a hook's
     *  context and every reminder that way), and so does the follow-up carrying a tool result's images: all of
     *  them belong to the tool's round. Reading only the item just before took the second such message for a
     *  new prompt, so the turn in flight lost its own reasoning and the input changed from its first reasoning
     *  item, a prompt-cache miss over the whole turn on the next round. */
    public fun withoutPriorTurns(input: List<JsonElement>): List<JsonElement> {
        val prompt = input.indices.lastOrNull { prompt(input, it) } ?: return input
        if ((0 until prompt).none { isReasoning(input[it]) }) return input
        return input.filterIndexed { at, item -> at >= prompt || !isReasoning(item) }
    }

    private fun prompt(input: List<JsonElement>, at: Int): Boolean {
        if (!isUserMessage(input[at])) return false
        val before = (at - 1 downTo 0).firstOrNull { !isUserMessage(input[it]) } ?: return true
        return !JsonScalars.str(input[before] as? JsonObject, ITEM_TYPE_KEY).orEmpty().endsWith(TOOL_OUTPUT_SUFFIX)
    }

    private fun isUserMessage(item: JsonElement): Boolean {
        val obj = item as? JsonObject ?: return false
        val type = JsonScalars.str(obj, ITEM_TYPE_KEY)
        return (type == null || type == MESSAGE_ITEM_TYPE) && JsonScalars.str(obj, ITEM_ROLE_KEY) == USER_ROLE_NAME
    }

    private fun isReasoning(item: JsonElement): Boolean =
        JsonScalars.str(item as? JsonObject, ITEM_TYPE_KEY) == REASONING_ITEM_TYPE
}

private const val ITEM_TYPE_KEY = "type"
private const val ITEM_ROLE_KEY = "role"
private const val USER_ROLE_NAME = "user"
private const val MESSAGE_ITEM_TYPE = "message"
private const val TOOL_OUTPUT_SUFFIX = "_output"
