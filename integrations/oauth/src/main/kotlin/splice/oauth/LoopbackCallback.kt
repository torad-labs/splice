// NEW: the loopback listener a browser login redirects back to, split out of LoginSpec so the spec
// stays within the constructor-width wall. Same package, so callers reach it unqualified.
package splice.oauth

/** The local redirect endpoint of an OAuth login: where the listener binds and which state it accepts. */
public data class LoopbackCallback(
    public val port: Int,
    public val path: String, // "/auth/callback" (codex) or "/callback" (grok)
    public val expectedState: String,
)
