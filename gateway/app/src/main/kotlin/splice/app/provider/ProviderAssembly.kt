// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.buildProvider + collaborator wiring) @ ed5c868 —
// invariants unchanged: the (dialect, auth.kind) dispatch, reduced to buildProvider plus the arm
// collaborators it holds. [probeScope] is the daemon's OWN scope, passed by reference on purpose —
// every auth provider built under this tree receives it as `prefetchScope`, and Daemon.stop()
// cancels exactly that scope. Constructing a second scope anywhere here would leave half the
// prefetch coroutines alive after stop().
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.GrokRefresh
import splice.app.KimiRefresh
import splice.app.TokenUrlRefreshCall
import splice.core.config.StatePaths
import splice.core.topology.Dialect
import splice.core.util.LogSink

/**
 * The (dialect, auth.kind) dispatch: everything that turns one head's resolved [ProviderBuild]
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
        val label = ctx.head.claude.command ?: ctx.key
        return when (ctx.providerCfg.dialect) {
            Dialect.OPENAI_RESPONSES -> responsesArm.responsesProvider(ctx, label)
            Dialect.OPENAI_CHAT -> chatArm.chatProvider(ctx, label)
            // anthropic-passthrough: kimi (device-oauth, x-api-key) or ANY anthropic-compatible
            // vendor (api-key Bearer, e.g. Moonshot's pay-per-token https://api.moonshot.ai/anthropic).
            Dialect.ANTHROPIC_PASSTHROUGH -> passthroughArm.passthroughProvider(ctx, label)
        }
    }
}
