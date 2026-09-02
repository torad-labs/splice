// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.buildProvider + collaborator wiring) @ ed5c868 —
// dispatch selects an arm by dialect, credentials by auth kind, and Kimi compatibility defaults by
// provider ID. [probeScope] is the daemon's OWN scope, passed by reference to every prefetching
// OAuth provider; Daemon.stop() cancels exactly that scope. Constructing another scope here would
// leave those prefetch coroutines alive after stop().
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.GrokRefresh
import splice.app.KimiRefresh
import splice.app.TokenUrlRefreshCall
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.util.LogSink

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
) {
    private val grokRefresh = GrokRefresh()
    private val kimiRefresh = KimiRefresh()
    private val passthroughAssembly = PassthroughAssembly()
    private val chatArm = ChatArm(probeScope, log, grokRefresh)
    private val kimiOAuth = KimiOAuth(probeScope, log, kimiRefresh)
    private val passthroughArm = PassthroughArm(statePaths, passthroughAssembly, kimiOAuth)
    private val grokResponsesArm = GrokResponsesArm(probeScope, log, grokRefresh)
    private val apiKeyResponsesArm = ApiKeyResponsesArm()
    private val responsesArm = ResponsesArm(probeScope, log, refreshCall, grokResponsesArm, apiKeyResponsesArm)

    // The dispatch that makes the daemon genuinely multi-provider: codex (responses+oauth), grok
    // (responses or chat + grok-oauth), openai-platform (responses+api-key, hash cache key),
    // and ANY openai-compatible vendor (chat dialect + api-key) — the last is pure TOML, zero code.
    internal fun buildProvider(ctx: ProviderBuild): Wired {
        requireCompatibleAuth(ctx)
        val label = ctx.head.claude.command ?: ctx.key
        return when (ctx.providerCfg.dialect) {
            Dialect.OPENAI_RESPONSES -> responsesArm.responsesProvider(ctx, label)
            Dialect.OPENAI_CHAT -> chatArm.chatProvider(ctx, label)
            // anthropic-passthrough: Kimi owns its Moonshot quirks and identity under OAuth or
            // API-key auth; every other compatible API-key vendor starts neutral and declares its
            // own wire facts in TOML.
            Dialect.ANTHROPIC_PASSTHROUGH -> passthroughArm.passthroughProvider(ctx, label)
        }
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
                "for provider '$provider' and dialect '${dialectWire(dialect)}'"
        }
    }

    private fun isCompatible(kind: AuthKind, dialect: Dialect, provider: String): Boolean = when (kind) {
        AuthKind.ChatgptOAuth -> dialect == Dialect.OPENAI_RESPONSES
        AuthKind.GrokOAuth -> dialect == Dialect.OPENAI_RESPONSES || dialect == Dialect.OPENAI_CHAT
        AuthKind.KimiOAuth -> dialect == Dialect.ANTHROPIC_PASSTHROUGH && provider == "kimi"
        AuthKind.Client -> dialect == Dialect.ANTHROPIC_PASSTHROUGH
    }

    private fun dialectWire(dialect: Dialect): String = when (dialect) {
        Dialect.OPENAI_RESPONSES -> "openai-responses"
        Dialect.OPENAI_CHAT -> "openai-chat"
        Dialect.ANTHROPIC_PASSTHROUGH -> "anthropic-passthrough"
    }
}
