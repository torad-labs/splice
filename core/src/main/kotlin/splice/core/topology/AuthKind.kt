// NEW: AuthKind — the auth-scheme registry (#924 Phase 2, tier-1). auth.kind's default-auth-file
// map was re-derived as byte-identical copies in StatusCommand AND SetupCommand; this is the single
// source. A SEALED hierarchy (project convention: sealed class, kt-no-sealed-interface) models only
// registered schemes whose behavior diverges. Api-key and custom kinds deliberately remain
// unregistered fallbacks. The TOML field therefore stays a raw String: an operator's unknown kind
// never fails config parse, from() returns null, and provider arms retain generic API-key behavior.
// Sign-in labels ARE registry data (the words splice login prints). Status and boot wording
// stays at the call site — StatusTable.backendLabel still words them its own way.
//
// 2026-09-05 — SPLICE OWNS ITS CREDENTIAL. Every OAuth kind defaults to a file of splice's own
// under ~/.config/splice/auth/, never the native app's (~/.codex/auth.json, ~/.grok/auth.json,
// ~/.kimi/credentials/kimi-code.json). A file shared with the vendor's CLI or desktop app shares one
// refresh-token family, and an OpenAI-style refresh ROTATES it: each side invalidates the other's
// session, and the head sits without credentials while the other process rewrites the file (24
// turns failed in the 16 s of one rotation at 18:31 that day). The native file stays an explicit
// opt-in through auth.file; `splice login <head>` signs splice in on its own, on whichever account
// the operator chooses — the split lets the native apps and the splice heads use different accounts.
package splice.core.topology

/** The wire spelling of the one scheme that is deliberately NOT a registered [AuthKind]: a key
 *  splice holds, named by env var, with no credential file and no refresh to model. It is still a
 *  domain fact, and it was being re-typed as a bare "api-key" literal at each call site — which
 *  ConstSingleSourceLawTest caught the moment a second file declared a const for it (2026-09-22).
 *  Compare through [AuthConfig.isApiKey] rather than against this directly wherever an AuthConfig
 *  is in hand. */
public const val API_KEY_WIRE: String = "api-key"

public sealed class AuthKind(
    public val wire: String,
    public val defaultAuthFile: String?,
    public val isOAuth: Boolean,
    public val signInLabel: String,
    /** The dialect on which code mode is available for this kind; null means never. */
    public val codeModeDialect: Dialect?,
) {
    /** OAuth schemes: a browser/device login mints a credential file. [authFile] is
     *  the splice-owned default for the kind — non-null here, so the legacy knobs and every arm can
     *  read it without a fallback literal of their own (header, 2026-09-05). [nativeAppFile] is the
     *  vendor's own CLI/app credential file: never a default, known so doctor can name a config
     *  that still shares it. */
    public sealed class OAuth(
        wire: String,
        public val authFile: String,
        public val nativeAppFile: String,
        public val nativeApp: String,
        signInLabel: String,
        codeModeDialect: Dialect? = null,
    ) : AuthKind(wire, authFile, isOAuth = true, signInLabel, codeModeDialect)

    public data object ChatgptOAuth : OAuth(
        "chatgpt-oauth",
        "~/.config/splice/auth/codex.json",
        "~/.codex/auth.json",
        "Codex CLI / ChatGPT app",
        "Codex (ChatGPT)",
        Dialect.OPENAI_RESPONSES,
    )

    public data object GrokOAuth : OAuth(
        "grok-oauth",
        "~/.config/splice/auth/grok.json",
        "~/.grok/auth.json",
        "Grok CLI",
        "Grok (xAI)",
    )

    // DR-98: the null here claimed a "provider-computed path", but nothing computes one — every
    // working path (device-flow login, the provider's credential read) hard-falls-back to this
    // same literal, while presence checks read the REGISTRY and so saw no file: a kimi-oauth head
    // with default config showed login-needed in status and FAILed doctor forever while serving.
    // The device identity (KimiDeviceIdentity) lives beside the file as device_id — splice's own
    // device under the splice-owned default, the native app's only when auth.file opts into its dir.
    public data object KimiOAuth : OAuth(
        "kimi-oauth",
        "~/.config/splice/auth/kimi.json",
        "~/.kimi/credentials/kimi-code.json",
        "Kimi CLI",
        "Kimi (Moonshot)",
    )

    public data object MuseOAuth : OAuth(
        "muse-oauth",
        "~/.config/splice/auth/muse.json",
        "~/.config/muse/auth.json",
        "Muse Code",
        "Muse (Meta)",
    )

    /** The head holds NO credential: the caller's own auth headers are forwarded upstream, and its
     *  native login stays enabled (campaign claude-head). No auth file, no refresh, no sign-in flow
     *  splice can run — which is why it is not an OAuth kind and has no default auth file. */
    public data object Client : AuthKind(
        "client",
        null,
        isOAuth = false,
        "Claude (client's own login)",
        codeModeDialect = null,
    )
}

/** Api-key heads stay unregistered on AuthKind. [ApiKeyProviderRow.id] and
 *  [ApiKeyProviderRow.aliases] are load-bearing for provider selection: ApiKeyResponsesArm reads
 *  the xai row (and its grok alias) to pick GrokProvider over OpenAiResponsesProvider. Labels
 *  still feed login UX. */
public data class ApiKeyProviderRow(
    public val id: String,
    public val label: String,
    public val tokenPattern: String?,
    public val aliases: Set<String> = emptySet(),
)

public object ApiKeyProviderRegistry {

    private val ROWS: List<ApiKeyProviderRow> = listOf(
        ApiKeyProviderRow("openrouter", "OpenRouter", "sk-or-[A-Za-z0-9_-]{20,}"),
        // DeepSeek keys are a FIXED shape: `sk-` plus exactly 32 lowercase alphanumerics, 35 total.
        // Pinned from two independent sources, not from a doc that only said "starts with sk-":
        // trufflehog's DeepSeek detector carries `\b(sk-[a-z0-9]{32})\b` and verifies it live against
        // api.deepseek.com/user/balance, and the operator's own stored key measures 35 with a
        // 32-character lower-plus-digit body. Tighter than OpenRouter's open-ended `{20,}`, and the
        // capture regex is quote-anchored to the WHOLE prompt, so a key discussed in prose is safe.
        // Erring tight fails SAFE: no capture only means the head falls back to `claude-deepseek login`.
        ApiKeyProviderRow("deepseek", "DeepSeek", "sk-[a-z0-9]{32}"),
        ApiKeyProviderRow("moonshot", "Moonshot", null),
        ApiKeyProviderRow("fireworks", "Fireworks", null),
        ApiKeyProviderRow("openai", "OpenAI", null),
        ApiKeyProviderRow("xai", "xAI", null, aliases = setOf("grok")),
    )

    public fun row(id: String): ApiKeyProviderRow? =
        ROWS.firstOrNull { it.id == id || id in it.aliases }

    /** The registered api-key provider rows. Exposed so login-matrix tests take their denominator
     *  from the registry rather than a second list that can omit a new id. */
    public fun rows(): List<ApiKeyProviderRow> = ROWS
}

/** The lookup half of [AuthKind] — the "registry" this file's header names. A named object since
 *  the 2026-08-16 style migration (HD-M8) made the companion illegal; same three function names,
 *  same bodies, same tolerance for an operator's custom kind (null, never a throw). */
public object AuthKindRegistry {

    private val KNOWN: List<AuthKind> = listOf(
        AuthKind.ChatgptOAuth,
        AuthKind.GrokOAuth,
        AuthKind.KimiOAuth,
        AuthKind.MuseOAuth,
        AuthKind.Client,
    )

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
