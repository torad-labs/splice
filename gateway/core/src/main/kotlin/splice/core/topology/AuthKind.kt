// NEW: AuthKind — the auth-scheme registry (#924 Phase 2, tier-1). auth.kind's default-auth-file
// map was re-derived as byte-identical copies in StatusCommand AND SetupCommand; this is the single
// source. A SEALED hierarchy (project convention: sealed class, kt-no-sealed-interface) models only
// registered schemes whose behavior diverges. Api-key and custom kinds deliberately remain
// unregistered fallbacks. The TOML field therefore stays a raw String: an operator's unknown kind
// never fails config parse, from() returns null, and provider arms retain generic API-key behavior.
// Context display labels stay in their call sites (status vs boot word them differently);
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

    // DR-98: the null here claimed a "provider-computed path", but nothing computes one — every
    // working path (device-flow login, the provider's credential read) hard-falls-back to this
    // same literal, while presence checks read the REGISTRY and so saw no file: a kimi-oauth head
    // with default config showed login-needed in status and FAILed doctor forever while serving.
    public data object KimiOAuth : OAuth("kimi-oauth", "~/.kimi/credentials/kimi-code.json")

    /** The head holds NO credential: the caller's own auth headers are forwarded upstream, and its
     *  native login stays enabled (campaign claude-head). No auth file, no refresh, no sign-in flow
     *  splice can run — which is why it is not an OAuth kind and has no default auth file. */
    public data object Client : AuthKind("client", null, isOAuth = false)
}

/** The lookup half of [AuthKind] — the "registry" this file's header names. A named object since
 *  the 2026-08-16 style migration (HD-M8) made the companion illegal; same three function names,
 *  same bodies, same tolerance for an operator's custom kind (null, never a throw). */
public object AuthKindRegistry {

    private val KNOWN: List<AuthKind> =
        listOf(AuthKind.ChatgptOAuth, AuthKind.GrokOAuth, AuthKind.KimiOAuth, AuthKind.Client)

    /** The registered schemes. Exposed so compatibility matrices derive their denominator from the
     *  registry rather than maintaining a second list that can silently omit a new kind. */
    public fun knownKinds(): List<AuthKind> = KNOWN

    /** The typed scheme for a wire kind, or null for an operator's custom/unknown kind. */
    public fun from(wire: String): AuthKind? = KNOWN.firstOrNull { it.wire == wire }

    /** Default auth-file path for a registered kind, or null when unknown or env-only. */
    public fun defaultAuthFileFor(wire: String): String? = from(wire)?.defaultAuthFile

    /** OAuth-ness by convention: any unregistered wire ending in `oauth` counts as future OAuth. */
    public fun isOAuth(wire: String): Boolean = from(wire)?.isOAuth ?: wire.endsWith("oauth")
}
