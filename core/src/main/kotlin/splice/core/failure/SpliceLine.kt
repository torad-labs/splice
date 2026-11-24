// NEW: failure-handling slice 2 (2026-10-26). A line a person reads, which only core can build: SpliceLines is its one
// author, so every surface that writes one (an ending, a notice line, a session note, an admission refusal) takes this
// type and cannot spell its own words.
package splice.core.failure

/** What every line splice writes to a person opens with. */
public const val SPLICE_PREFIX: String = "[splice] "

/** One plain line a person reads in Claude Code, composed by [SpliceLines] and nowhere else. */
@JvmInline
public value class SpliceLine internal constructor(public val text: String) {
    /** The same words without the prefix: what the turn's trace records, so the trace says what the client read. */
    public val sentence: String get() = text.removePrefix(SPLICE_PREFIX)
}
