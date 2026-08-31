// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.chatProvider) @ ed5c868. ProviderAssembly rejects
// registered incompatible kinds first; this arm selects Grok OAuth and sends unregistered
// api-key/custom kinds to generic Bearer auth. Grok rides this dialect because
// /v1/chat/completions streams the full readable CoT (`reasoning_content`) where the Responses
// summary channel stops mid-reasoning
// (measured 2026-07-18; grok CLI / OpenCode parity).
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.GrokRefresh
import splice.app.TopologyLoader
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.dialect.chat.ChatQuirks
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.spi.ProviderTuning
import java.nio.file.Paths

internal class ChatArm(
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val grokRefresh: GrokRefresh,
) {
    // After compatibility validation: Grok OAuth uses refresh-capable auth; unregistered
    // api-key/custom kinds use generic Bearer auth. Grok rides this dialect because
    // /v1/chat/completions streams the full readable CoT (`reasoning_content`) where Responses
    // stops mid-reasoning (measured 2026-07-18; grok CLI / OpenCode parity).
    internal fun chatProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val auth = when (providerCfg.auth.kind) {
            GROK_OAUTH -> {
                val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
                GrokAuthProvider(
                    authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.grok/auth.json")),
                    authCacheMs = ctx.cfg.authCacheMs,
                    refreshCall = { rt -> grokRefresh.refresh(tokenUrl, rt) },
                    prefetchScope = probeScope,
                    // JW-03: [<headKey>] first, so [grok-auth] refresh lines reach the head's tail
                    log = HeadScopedLogs.headScopedLog(ctx.key, log),
                )
            }
            else -> ApiKeyAuthProvider(
                envVar = providerCfg.auth.effectiveApiKeyEnv(key),
                keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
            )
        }
        return Wired(
            OpenAiChatProvider(
                tuning = ProviderTuning(
                    key = key,
                    label = label,
                    catalog = ctx.catalog,
                    pinnedModel = ctx.head.pinnedModel,
                    auth = auth,
                    baseUrl = providerCfg.baseUrl,
                    watchdog = ctx.watchdog,
                    loginCommand = ctx.loginCommand,
                ),
                // grok-oauth rides session-pinned prompt caching + opt-in usage frames (probed
                // 2026-07-19: 135k tokens, 1.7-2.8s TTFB, 99.97% cached — the two gaps that sank
                // the 07-18 chat-dialect attempt). Unknown api-key vendors keep the bare quirks.
                quirks = (
                    if (providerCfg.auth.kind == GROK_OAUTH) {
                        ChatQuirks(providerTag = key, sessionCacheKeyPrefix = label, emitUsageInStream = true)
                    } else {
                        ChatQuirks(providerTag = key)
                    }
                    ).withReasoningEffortToml(providerCfg.quirks.reasoningEffort),
                showReasoning = ctx.cfg.showReasoning,
            ),
            auth,
        )
    }
}
