// NEW: (NF-06, no Node source) the runaway-upstream buffer guard, ONE definition for every stream translator — the
// same single-source move TerminalStates made for terminal precedence. spliced is one process
// serving every head: an upstream streaming deltas forever without a terminal event does not
// fail a turn, it OOMs the ~1G heap and takes codex+grok+kimi down simultaneously. Translators
// latch a local runaway message when this trips and stop feeding their buffers; the turn ends
// as an honest non-provider-reported API_ERROR, never a crash.
package splice.spi

public object BufferCapacity {
    /** Far above any legitimate response: 200K-token completions run well under 1M chars. */
    public const val MAX_BUFFERED_CHARS: Int = 20_000_000

    /** Real turns use a handful of tool calls; 50k index entries is upstream misbehavior. */
    public const val MAX_TOOL_INDEX_ENTRIES: Int = 50_000

    /** True once any accumulation surface passed its cap. [pendingArgsLen] covers chars that
     *  accumulate BEFORE a block opens (deferred tool opens, reasoning envelopes) — an entry
     *  count alone leaves those unbounded. */
    public fun over(
        textLen: Int,
        thinkingLen: Int,
        toolIndexCount: Int = 0,
        pendingArgsLen: Int = 0,
    ): Boolean =
        textLen >= MAX_BUFFERED_CHARS || thinkingLen >= MAX_BUFFERED_CHARS ||
            toolIndexCount >= MAX_TOOL_INDEX_ENTRIES || pendingArgsLen >= MAX_BUFFERED_CHARS
}
