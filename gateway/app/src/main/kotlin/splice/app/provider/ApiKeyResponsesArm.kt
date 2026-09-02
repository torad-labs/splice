// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.apiKeyResponsesProvider) @ ed5c868 — invariants
// unchanged: api-key + responses: grok (session-id cache key) vs openai platform
// (first-message-hash). Reasoning display knobs come from ConfigService (TOML [daemon] / env / state).
package splice.app.provider

import splice.app.TopologyLoader
import splice.provider.grok.GrokProvider
import splice.provider.grok.GrokQuirks
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiQuirks
import splice.provider.openai.OpenAiResponsesProvider
import splice.spi.ProviderTuning
import java.nio.file.Paths

internal class ApiKeyResponsesArm {
    private val quirksOverlay = QuirksOverlay()

    // api-key + responses: grok (session-id cache key) vs openai platform (first-message-hash).
    // Reasoning display knobs come from ConfigService (TOML [daemon] / env / state).
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
        val provider = if (providerCfg.quirks.cacheKey == "session-id") {
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
