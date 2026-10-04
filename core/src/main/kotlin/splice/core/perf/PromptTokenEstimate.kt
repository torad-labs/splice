// NEW: V4-446 — the existing local count_tokens byte estimate, shared with Codex preflight.
package splice.core.perf

// why: the local count_tokens endpoint uses three UTF-8 bytes per estimated input token.
private const val ESTIMATE_BYTES_PER_TOKEN: Long = 3

/** An estimate, not a tokenizer or a hard upper bound; exact input comes from a completed turn. */
public object PromptTokenEstimate {
    public fun fromBytes(bytes: Long): Long = ((bytes + ESTIMATE_BYTES_PER_TOKEN - 1) / ESTIMATE_BYTES_PER_TOKEN)
        .coerceAtLeast(1)
}
