package splice.dialect.anthropic

private val BLOCK_KINDS = setOf(
    "text",
    "thinking",
    "redacted_thinking",
    "tool_use",
    "server_tool_use",
    "web_search_tool_result",
)
private val STOP_REASONS = setOf(
    // ast-grep-ignore: kt-l3-end-turn-literal — describing upstream evidence, never emitting a wire terminal
    "end_turn",
    "stop_sequence",
    "tool_use",
    "max_tokens",
    "refusal",
    "pause_turn",
    "model_context_window_exceeded",
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
        if (reason.isNotEmpty()) stopReason = reason.takeIf { it in STOP_REASONS } ?: "other"
    }

    fun describe(): String = "opened=[${counts(opened)}] closed=[${counts(closed)}] stop_reason=$stopReason"

    private fun counts(kinds: Map<String, Int>): String =
        kinds.entries.joinToString(",") { (kind, count) -> "$kind:$count" }
}
