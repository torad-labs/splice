// NEW: 2026-10-09 — the Anthropic stream's upstream frame types and stop reasons, as names this dialect READS. The wire
// spelling is the lowercased constant name, so no literal of a wire terminal appears outside SseEmitter (kt-l3-*) and the
// two readers need no ast-grep-ignore to say they only read.
package splice.dialect.anthropic

/** The frame `type` values an upstream Anthropic SSE stream carries, as the router dispatches on them. */
internal enum class UpstreamFrame {
    MESSAGE_START,
    CONTENT_BLOCK_START,
    CONTENT_BLOCK_DELTA,
    CONTENT_BLOCK_STOP,
    MESSAGE_DELTA,
    MESSAGE_STOP,
    ERROR,
    ;

    /** The spelling on the wire. */
    val wire: String = name.lowercase()
}

/** The `stop_reason` values the diagnostics describe; any other vendor string reads as "other". */
internal enum class UpstreamStopReason {
    END_TURN,
    STOP_SEQUENCE,
    TOOL_USE,
    MAX_TOKENS,
    REFUSAL,
    PAUSE_TURN,
    MODEL_CONTEXT_WINDOW_EXCEEDED,
    ;

    /** The spelling on the wire. */
    val wire: String = name.lowercase()
}

/** Reads the wire spelling of upstream frames and stop reasons. */
internal object UpstreamVocabulary {
    private val frames = UpstreamFrame.entries.associateBy { it.wire }
    val stopReasons: Set<String> = UpstreamStopReason.entries.map { it.wire }.toSet()

    /** The frame type [wire] names; null for one this dialect relays untouched. */
    fun frame(wire: String): UpstreamFrame? = frames[wire]
}
