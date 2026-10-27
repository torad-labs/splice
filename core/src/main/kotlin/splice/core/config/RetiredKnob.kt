// NEW: failure-handling slice 4. The knobs the redesign retired, kept by name so a setting that still uses one is
// refused as retired instead of read as nothing. upstreamRetries and the retryBackoff* curve gave way to the retry
// engine, which takes how often and how soon from what failed (RetryBudgets); the fold knobs gave way to codex's own
// reading of a round its backend cut (CodexReplies), continued on the turn's one ledger. A person can still have any of
// them set in splice.toml, in splice's environment or in the settings splice saved, and the environment and the saved
// settings win over splice.toml, so each place is a boot finding (final-design.md section 2).
package splice.core.config

/** A knob splice no longer has: its key, the environment variables that set it, and the `[daemon]` field that did. */
public enum class RetiredKnob(
    public val key: String,
    public val envNames: List<String>,
    /** The snake_case field `[daemon]` spelled it with, for the knobs that had one. */
    public val daemonField: String?,
    private val gaveWayTo: String,
) {
    UPSTREAM_RETRIES("upstreamRetries", listOf("CLAUDEX_UPSTREAM_RETRIES"), null, RETRY_ENGINE),
    RETRY_BACKOFF_BASE_MS("retryBackoffBaseMs", listOf("SPLICE_RETRY_BACKOFF_BASE_MS"), null, RETRY_ENGINE),
    RETRY_BACKOFF_CAP_MS("retryBackoffCapMs", listOf("SPLICE_RETRY_BACKOFF_CAP_MS"), null, RETRY_ENGINE),
    RETRY_BACKOFF_JITTER_PCT("retryBackoffJitterPct", listOf("SPLICE_RETRY_BACKOFF_JITTER_PCT"), null, RETRY_ENGINE),
    FOLD_REASONING_MODELS(
        "foldReasoningModels",
        listOf("CLAUDEX_FOLD_REASONING_MODELS"),
        "fold_reasoning_models",
        CODEX_FOLD,
    ),
    FOLD_MAX_CONTINUE("foldMaxContinue", listOf("CLAUDEX_FOLD_MAX_CONTINUE"), "fold_max_continue", CODEX_FOLD),
    FOLD_MARKER_TEXT("foldMarkerText", listOf("CLAUDEX_FOLD_MARKER_TEXT"), "fold_marker_text", CODEX_FOLD),
    FOLD_MAX_TIER("foldMaxTier", listOf("CLAUDEX_FOLD_MAX_TIER"), "fold_max_tier", CODEX_FOLD),
    ;

    /** Why the setting no longer does anything, as a finding says it. It never quotes a value. */
    public val retired: String get() = "was retired in 0.4.0, since splice now $gaveWayTo"
}

private const val RETRY_ENGINE = "decides how often and how soon a failed request is sent again from what failed"
private const val CODEX_FOLD = "continues on its own a codex answer whose reasoning the backend cut short"

/** Each retired knob by the key splice.toml's knob tables, a PATCH and the saved settings spell it with. */
public val retiredKnobsByKey: Map<String, RetiredKnob> = RetiredKnob.entries.associateBy { it.key }
