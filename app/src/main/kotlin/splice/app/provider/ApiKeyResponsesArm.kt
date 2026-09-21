// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.apiKeyResponsesProvider) @ ed5c868 — invariants
// unchanged: api-key + responses: GrokProvider vs OpenAiResponsesProvider. Registry ids xai/grok
// plus the dated session-id cache_key arm. Reasoning display knobs come from ConfigService.
package splice.app.provider

import splice.app.daemon.TopologyLoader
import splice.core.topology.ApiKeyProviderRegistry
import splice.provider.grok.GrokProvider
import splice.provider.grok.GrokQuirks
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiQuirks
import splice.provider.openai.OpenAiResponsesProvider
import splice.upstream.ProviderTuning
import java.nio.file.Paths

internal class ApiKeyResponsesArm {
    private val quirksOverlay = QuirksOverlay()

    // api-key + responses: GrokProvider vs OpenAiResponsesProvider. Reasoning display knobs come
    // from ConfigService (TOML [daemon] / env / state).
    internal fun apiKeyResponsesProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        val auth = ApiKeyAuthProvider(
            envVar = providerCfg.auth.effectiveApiKeyEnv(key),
            keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
        )
        // Identical in both branches — factored out so adding loginCommand didn't push this past
        // detekt's LongMethod ceiling with a second duplicated ProviderTuning block.
        val tuning = ProviderTuning(
            key = key,
            label = label,
            catalog = catalog,
            pinnedModel = head.pinnedModel,
            auth = auth,
            baseUrl = providerCfg.baseUrl,
            watchdog = watchdog,
            loginCommand = ctx.loginCommand,
        )
        // Registry ids xai and grok mean this vendor. 2026-09-15 compatibility: pre-V4-21 this arm
        // selected GrokProvider by quirks.cache_key == session-id, not by the table name. Keep that
        // trigger so a table not named xai/grok that already set session-id still gets GrokProvider.
        val grok = ApiKeyProviderRegistry.row(head.provider)?.id == "xai" ||
            providerCfg.quirks.cacheKey == "session-id"
        val provider = if (grok) {
            GrokProvider(
                tuning = tuning,
                showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning,
                configEffort = cfg.effort,
                configSummary = cfg.summary,
                quirks = quirksOverlay.responsesQuirks(providerCfg, GrokQuirks().defaultQuirks(), cfg),
            )
        } else {
            OpenAiResponsesProvider(
                tuning = tuning,
                showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning,
                configEffort = cfg.effort,
                configSummary = cfg.summary,
                quirks = quirksOverlay.responsesQuirks(providerCfg, OpenAiQuirks().defaultQuirks(), cfg),
            )
        }
        return Wired(provider, auth)
    }
}
