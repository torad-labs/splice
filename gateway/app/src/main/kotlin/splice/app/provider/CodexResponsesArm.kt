// NEW: Codex owns its Responses path: ChatGPT OAuth accounts, token refresh, and code-mode bridge.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.TokenUrlRefreshCall
import splice.app.TopologyLoader
import splice.app.auth.OAuthAccountFiles
import splice.app.codemode.DEFAULT_ADVANCE_TIMEOUT_MS
import splice.app.codemode.DEFAULT_HEAP_MB
import splice.app.codemode.DEFAULT_MAX_WORKERS
import splice.app.codemode.JvmCodeModeRuntime
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.upstream.ProviderTuning
import java.nio.file.Path
import java.nio.file.Paths

internal class CodexResponsesArm(
    private val statePaths: StatePaths,
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val refreshCall: TokenUrlRefreshCall,
) {
    private val quirksOverlay = QuirksOverlay()
    private val accountFiles = OAuthAccountFiles()

    internal fun codexOAuthProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        val primaryPath = Paths.get(
            TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.codexAuthPath),
        )
        val accounts = codexAccounts(ctx, primaryPath)
        val auth = WiredAccounts.providerAccount(accounts).auth
        return Wired(
            CodexProvider(
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
                quirks = quirksOverlay.responsesQuirks(providerCfg, CodexQuirks().defaultQuirks(), cfg),
                // Reasoning-continuation folding (codex 518n-2) — codex head ONLY; grok/openai
                // never receive a fold config, so they stay pure passthrough.
                foldConfig = quirksOverlay.foldConfigFrom(cfg),
                accountIdHeader = providerCfg.quirks.accountIdHeader,
                codeModeBridge = codeModeBridge(ctx),
            ),
            auth,
            accounts,
        )
    }

    private fun codexAccounts(ctx: ProviderBuild, primaryPath: Path): List<WiredAccount> {
        // Refresh hits the OAuth ISSUER's token endpoint (auth.openai.com), not the API base_url.
        val tokenUrl = CodexOAuthEndpoints.tokenUrl(System::getenv)
        return accountFiles.discover(AuthKind.ChatgptOAuth, primaryPath).map { file ->
            WiredAccount(
                label = file.label,
                primary = file.primary,
                auth = codexAuth(ctx, file.credentialFile, tokenUrl),
                quotaFile = file.quotaFile,
                credentialPresent = file.credentialPresent,
            )
        }
    }

    private fun codexAuth(ctx: ProviderBuild, path: Path, tokenUrl: String): RefreshableAuthProvider =
        CodexAuthProvider(
            authPath = path,
            authCacheMs = ctx.cfg.authCacheMs,
            refreshCall = { refreshToken -> refreshCall(tokenUrl, refreshToken) },
            prefetchScope = probeScope,
            // JW-03: [<headKey>] first, so [codex-auth] refresh lines reach the head's tail.
            log = HeadScopedLogs.headScopedLog(ctx.key, log),
        )

    private fun codeModeBridge(ctx: ProviderBuild): CodexCodeModeBridge? =
        if (ctx.providerCfg.codeModeEnabled) {
            CodexCodeModeBridge(
                CodeModeBridgeConfig(
                    runtime = JvmCodeModeRuntime(
                        // V4-110: the three code-mode pool knobs are TOML quirks overlaid on the code
                        // defaults — absent keeps today's 4 workers / 5s advance / 128MB heap.
                        maxWorkers = ctx.providerCfg.quirks.codeModeWorkers ?: DEFAULT_MAX_WORKERS,
                        advanceTimeoutMs = ctx.providerCfg.quirks.codeModeTimeoutMs ?: DEFAULT_ADVANCE_TIMEOUT_MS,
                        heapMb = ctx.providerCfg.quirks.codeModeHeapMb ?: DEFAULT_HEAP_MB,
                    ),
                    stateFile = statePaths.stateDir.resolve("${ctx.key}-code-mode.json"),
                    log = HeadScopedLogs.headScopedLog(ctx.key, log),
                ),
            )
        } else {
            null
        }
}
