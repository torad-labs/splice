// NEW: AuthKind — the auth-scheme registry (#924 Phase 2, tier-1). auth.kind's default-auth-file
// map was re-derived as byte-identical copies in StatusCommand AND SetupCommand; this is the single
// source. A SEALED hierarchy (project convention: sealed class, kt-no-sealed-interface): auth
// schemes diverge in behaviour — OAuth kinds have a refresh + device/browser login flow and a
// credential file, an api-key kind has neither — so each variant can grow its own members. Kept as
// a LOOKUP over a raw String, not a closed field type: auth.kind stays a String at the TOML boundary
// so an operator's custom/unknown kind never fails config parse — from() returns null and callers
// fall back. Context display labels stay in their call sites (status vs boot word them differently);
// only the shared facts live here.
package splice.core.topology

public sealed class AuthKind(
    public val wire: String,
    public val defaultAuthFile: String?,
    public val isOAuth: Boolean,
) {
    /** OAuth schemes: a browser/device login mints a refresh-capable credential file. */
    public sealed class OAuth(wire: String, defaultAuthFile: String?) :
        AuthKind(wire, defaultAuthFile, isOAuth = true)

    public data object ChatgptOAuth : OAuth("chatgpt-oauth", "~/.codex/auth.json")
    public data object GrokOAuth : OAuth("grok-oauth", "~/.grok/auth.json")
    public data object KimiOAuth : OAuth("kimi-oauth", null) // device-flow token at a provider-computed path

    public companion object {
        private val KNOWN: List<AuthKind> = listOf(ChatgptOAuth, GrokOAuth, KimiOAuth)

        /** The typed scheme for a wire kind, or null for an operator's custom/unknown kind. */
        public fun from(wire: String): AuthKind? = KNOWN.firstOrNull { it.wire == wire }

        /** Default auth-file path for a known kind, or null (unknown kind / env-only auth). */
        public fun defaultAuthFileFor(wire: String): String? = from(wire)?.defaultAuthFile

        /** OAuth-ness by convention: any `*-oauth` wire kind (covers a future kind too). */
        public fun isOAuth(wire: String): Boolean = from(wire)?.isOAuth ?: wire.endsWith("oauth")
    }
}
