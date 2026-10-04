// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.buildProvider + collaborator wiring) @ ed5c868 —
// dispatch selects an arm by dialect, credentials by auth kind, and Kimi compatibility defaults by
// provider ID. [probeScope] is the daemon's OWN scope, passed by reference to every prefetching
// OAuth provider; Daemon.stop() cancels exactly that scope. Constructing another scope here would
// leave those prefetch coroutines alive after stop().
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.TokenUrlRefreshCall
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.util.LogSink
import splice.oauth.grok.GrokRefresh
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The dialect/auth/provider-ID dispatch: everything that turns one head's resolved [ProviderBuild]
 * into the provider + auth pair it serves with. [probeScope] is the daemon's OWN scope, passed by
 * reference on purpose — see file header.
 */
internal class ProviderAssembly(
    statePaths: StatePaths,
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val refreshCall: TokenUrlRefreshCall,
    private val museArm: MuseResponsesArm = MuseResponsesArm(statePaths, log, probeScope),
    private val kimiArm: KimiPassthroughArm = KimiPassthroughArm(statePaths, probeScope, log),
) {
    private val grokRefresh = GrokRefresh(log)
    private val passthroughAssembly = PassthroughAssembly()
    private val chatArm = ChatArm(probeScope, log, grokRefresh)
    private val passthroughArm = PassthroughArm(passthroughAssembly, ClaudeAccountWiring(statePaths, log))
    private val grokResponsesArm = GrokResponsesArm(probeScope, log, grokRefresh, statePaths)
    private val apiKeyResponsesArm = ApiKeyResponsesArm(statePaths)
    private val codexResponsesArm = CodexResponsesArm(statePaths, probeScope, log, refreshCall)
    private val responsesArm = ResponsesArm(grokResponsesArm, apiKeyResponsesArm, codexResponsesArm)
    private val legacyMuseDialectLogged = AtomicBoolean()
    private val legacyMuseBaseLogged = AtomicBoolean()

    // The dispatch that makes the daemon genuinely multi-provider: codex (responses+oauth), grok
    // (responses or chat + grok-oauth), openai-platform (responses+api-key, hash cache key),
    // and ANY openai-compatible vendor (chat dialect + api-key) — the last is pure TOML, zero code.
    internal fun buildProvider(ctx: ProviderBuild): Wired {
        requireCompatibleAuth(ctx)
        val label = ctx.head.claude.command ?: ctx.key
        if (ctx.providerCfg.auth.kind == MUSE_OAUTH) return museArm.museOauthProvider(museCompatible(ctx), label)
        if (ctx.providerCfg.auth.kind == KIMI_OAUTH) return kimiArm.kimiOauthProvider(ctx, label)
        return when (ctx.providerCfg.dialect) {
            Dialect.OPENAI_RESPONSES -> responsesArm.responsesProvider(ctx, label)
            Dialect.OPENAI_CHAT -> chatArm.chatProvider(ctx, label)
            // anthropic-passthrough: provider id kimi (not client) is the Moonshot api-key path.
            // CLIENT stays on PassthroughArm so a kimi-named client head does not grow X-Msh headers.
            Dialect.ANTHROPIC_PASSTHROUGH ->
                if (ctx.head.provider == "kimi" && ctx.providerCfg.auth.kind != CLIENT) {
                    kimiArm.kimiApiKeyProvider(ctx, label)
                } else {
                    passthroughArm.passthroughProvider(ctx, label)
                }
        }
    }

    /** Older Muse topology still boots on Responses while doctor names both declarations to fix. */
    private fun museCompatible(ctx: ProviderBuild): ProviderBuild {
        val declared = ctx.providerCfg
        if (declared.dialect == Dialect.ANTHROPIC_PASSTHROUGH &&
            legacyMuseDialectLogged.compareAndSet(false, true)
        ) {
            log(
                "[muse][boot] stale dialect = \"anthropic-passthrough\"; serving Muse on Responses; " +
                    "set dialect = \"openai-responses\" in [providers.muse]\n",
            )
        }
        val base = declared.baseUrl.trimEnd('/')
        val responsesBase = if (base.endsWith("/v1")) base else "$base/v1"
        if (responsesBase != base && legacyMuseBaseLogged.compareAndSet(false, true)) {
            log(
                "[muse][boot] stale base_url without /v1; serving Muse at /v1/responses; " +
                    "set base_url = \"$responsesBase\" in [providers.muse]\n",
            )
        }
        return ctx.copy(providerCfg = declared.copy(dialect = Dialect.OPENAI_RESPONSES, baseUrl = responsesBase))
    }

    /** Registered auth kinds are promises with a finite compatibility matrix. Kimi OAuth also binds
     *  to the Kimi provider ID that owns its Moonshot wire identity. Unknown/custom kinds
     *  intentionally retain the api-key fallback in each arm. */
    private fun requireCompatibleAuth(ctx: ProviderBuild) {
        val kind = AuthKindRegistry.from(ctx.providerCfg.auth.kind) ?: return
        val dialect = ctx.providerCfg.dialect
        val provider = ctx.head.provider
        require(isCompatible(kind, dialect, provider)) {
            "head '${ctx.key}' has incompatible auth kind '${kind.wire}' " +
                "for provider '$provider' and dialect '${DialectWires.name(dialect)}'"
        }
    }

    private fun isCompatible(kind: AuthKind, dialect: Dialect, provider: String): Boolean = when (kind) {
        AuthKind.ChatgptOAuth -> dialect == Dialect.OPENAI_RESPONSES
        AuthKind.GrokOAuth -> dialect == Dialect.OPENAI_RESPONSES || dialect == Dialect.OPENAI_CHAT
        AuthKind.KimiOAuth -> dialect == Dialect.ANTHROPIC_PASSTHROUGH && provider == "kimi"
        AuthKind.MuseOAuth -> museDialectAllowed(dialect, provider)
        AuthKind.Client -> dialect == Dialect.ANTHROPIC_PASSTHROUGH
    }

    private fun museDialectAllowed(dialect: Dialect, provider: String): Boolean =
        provider == "muse" && (dialect == Dialect.OPENAI_RESPONSES || dialect == Dialect.ANTHROPIC_PASSTHROUGH)
}
