// NEW: (CX-02) the compaction directive, as ONE definition every dialect shares.
//
// splice recognises a compaction turn in one shared place (the gateway's Compact.kt markers,
// tools-agnostic, used by all heads), but only the Responses builder ever ACTED on that
// recognition: chat and passthrough merely stripped tools. On a kimi, openrouter, moonshot,
// ollama or LM-Studio head the backend was therefore never told it was summarizing, and a chatty
// "Sure, I can help with that." was stored SILENTLY as the session's summary — the same
// clean-looking-but-wrong failure the honesty walls exist to remove.
//
// This lives in :core because that is the only module all three dialect builders depend on
// (Compact.kt is in :gateway, which depends on the dialects, not the reverse). The text is the
// verbatim Responses wording, moved rather than rewritten, so the Responses wire bytes are
// unchanged. On drift: edit HERE — a builder that re-spells the directive locally is a drifting
// copy, and the CX-02 wall fails on it.
package splice.core.turn

/** The directive's sentinel first token — what the wall, the canary tests and a log grep key on. */
public const val COMPACT_DIRECTIVE_HEAD: String = "COMPACT MODE (critical):"

/** The directive as the dialects emit it: one block, newline-separated. */
public val compactDirective: String = listOf(
    "$COMPACT_DIRECTIVE_HEAD You are summarizing a coding session for another agent.",
    "Respond with ONLY a detailed plain-text summary. No tools. No function calls.",
    "Do not put the summary only in reasoning — the final message text MUST contain the full summary.",
    "Structure with headings: Goal, Decisions, Files touched, Current state, Errors, Next steps, Constraints.",
    "Be concrete (paths, commands, numbers). Omit boilerplate.",
).joinToString("\n")

/** The composer, as a named object since the 2026-08-16 style migration (HD-M8) — the directive text
 *  itself stays a top-level `val` above, which is what the CX-02 wall and every canary key on. */
public object CompactInstructions {

    /** [base] with the directive appended on a compact turn, empty parts dropped. The shared shape:
     *  every dialect that carries its system prompt as TEXT composes it exactly this way. */
    public fun withCompactDirective(base: String?, compact: Boolean): String =
        if (!compact) {
            base.orEmpty()
        } else {
            listOf(base.orEmpty(), compactDirective).filter { it.isNotEmpty() }.joinToString("\n")
        }
}
