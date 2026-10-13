// NEW: (no Node source) a content-free description of an Anthropic stream's block and stop structure, for empty-turn diagnostics
package splice.dialect.anthropic

private val BLOCK_KINDS = setOf(
    "text",
    "thinking",
    "redacted_thinking",
    "tool_use",
    "server_tool_use",
    "web_search_tool_result",
)

/** Backend block lifecycles only: local retirement and closeAll never count as backend stops.
 *  Retains whitelisted kinds and counts, not content, tool names, ids or arbitrary vendor strings.
 *  The driver's existing block-start capacity valve bounds the index map. */
internal class PassthroughOutputShape {
    private val active = mutableMapOf<Int, String>()
    private val opened = linkedMapOf<String, Int>()
    private val closed = linkedMapOf<String, Int>()
    private var stopReason = "none"

    fun openBlock(index: Int?, type: String) {
        index ?: return
        val kind = type.takeIf { it in BLOCK_KINDS } ?: "other"
        active[index] = kind
        opened[kind] = opened.getOrDefault(kind, 0) + 1
    }

    fun closeBlock(index: Int?) {
        val kind = active.remove(index) ?: return
        closed[kind] = closed.getOrDefault(kind, 0) + 1
    }

    fun onStopReason(reason: String) {
        if (reason.isNotEmpty()) stopReason = reason.takeIf { it in UpstreamVocabulary.stopReasons } ?: "other"
    }

    /** The model ended its message by itself, on end_turn or stop_sequence: a finished answer even when it holds
     *  nothing, as Claude Code's prompt-suggestion requests ask for ("Or nothing"). max_tokens, refusal and the
     *  rest are not, and neither is a stream with no stop_reason. */
    val endedByModel: Boolean get() = stopReason in MODEL_ENDINGS

    fun describe(): String = "opened=[${counts(opened)}] closed=[${counts(closed)}] stop_reason=$stopReason"

    private fun counts(kinds: Map<String, Int>): String =
        kinds.entries.joinToString(",") { (kind, count) -> "$kind:$count" }
}

private val MODEL_ENDINGS = setOf(UpstreamStopReason.END_TURN.wire, UpstreamStopReason.STOP_SEQUENCE.wire)
