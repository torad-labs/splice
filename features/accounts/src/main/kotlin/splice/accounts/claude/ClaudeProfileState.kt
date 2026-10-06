// NEW: provider profile outcome, independent of usable inference credentials or quota observations.
package splice.accounts.claude

/** Only the current credential's classification is public, never its digest or provider refusal text. */
public enum class ClaudeProfileState(public val wire: String) {
    VERIFIED("verified"),
    PENDING("pending"),
    REFUSED("refused"),
}
