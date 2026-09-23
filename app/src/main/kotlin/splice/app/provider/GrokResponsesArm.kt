// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.grokOAuthProvider) @ ed5c868 — invariants
// unchanged: grok via the SuperGrok/X-Premium+ browser OAuth (~/.grok/auth.json, Bearer +
// refresh) — the same Responses dialect + grok quirks, only the auth differs from the api-key path.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.auth.GrokRefresh
import splice.core.util.LogSink
import splice.provider.grok.GrokProvider
import splice.provider.grok.GrokQuirks
import splice.topology.TopologyLoader
import splice.upstream.ProviderTuning
import java.nio.file.Paths

internal class GrokResponsesArm(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val grokRefresh: GrokRefresh,
) {
    private val quirksOverlay = QuirksOverlay()
    private val grokAccounts = GrokAccountWiring(probeScope, log, grokRefresh)

    // grok via the SuperGrok/X-Premium+ browser OAuth (~/.grok/auth.json, Bearer + refresh) — the
    // same Responses dialect + grok quirks, only the auth differs from the api-key path.
    internal fun grokOAuthProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        val primaryPath = Paths.get(
            TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.grokAuthPath),
        )
        val accounts = grokAccounts.accounts(ctx, primaryPath)
        val auth = WiredAccounts.providerAccount(accounts).auth
        return Wired(
            GrokProvider(
                tuning = ProviderTuning(
                    key = key,
                    label = label,
                    catalog = catalog,
                    pinnedModel = head.pinnedModel,
                    auth = auth,
                    baseUrl = providerCfg.baseUrl,
                    watchdog = watchdog,
                    loginCommand = ctx.loginCommand,
                ),
                showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning,
                configEffort = cfg.effort,
                configSummary = cfg.summary,
                quirks = quirksOverlay.responsesQuirks(providerCfg, GrokQuirks().defaultQuirks(), cfg),
            ),
            auth,
            accounts,
        )
    }
}
