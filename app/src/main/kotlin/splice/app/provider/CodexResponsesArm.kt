// NEW: Codex owns its Responses path: ChatGPT OAuth accounts, token refresh, and code-mode bridge.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.app.TokenUrlRefreshCall
import splice.app.provider.codex.CodeModeSessionLiveness
import splice.codemode.DEFAULT_ADVANCE_TIMEOUT_MS
import splice.codemode.DEFAULT_HEAP_MB
import splice.codemode.DEFAULT_MAX_WORKERS
import splice.codemode.DEFAULT_POOL_MEMORY_MB
import splice.codemode.JvmCodeModeRuntime
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.CODE_MODE_DIR
import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.dialect.responses.ReasoningSettings
import splice.oauth.OAuthAccountFiles
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeCellLease
import splice.provider.codex.CodeModeOnlyModels
import splice.provider.codex.CodeModeSessionAlive
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.topology.TopologyLoader
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.nio.file.Path
import java.nio.file.Paths

internal class CodexResponsesArm(
    private val statePaths: StatePaths,
    private val probeScope: CoroutineScope,
    private val log: LogSink,
    private val refreshCall: TokenUrlRefreshCall,
    private val sessionAlive: CodeModeSessionAlive = CodeModeSessionLiveness(probeScope, log = log),
) {
    private val quirksOverlay = QuirksOverlay()
    private val accountFiles = OAuthAccountFiles()

    internal fun codexOAuthProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.faultPlan.watchdog
        val cfg = ctx.cfg
        val primaryPath = Paths.get(
            TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.codexAuthPath),
        )
        val accounts = codexAccounts(ctx, primaryPath)
        val auth = WiredAccounts.providerAccount(accounts).auth
        return Wired(
            CodexProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = key, label = label),
                    catalog = catalog,
                    pinnedModel = head.pinnedModel,
                    auth = auth,
                    locations = ProviderLocations(
                        baseUrl = providerCfg.baseUrl,
                        stateDir = statePaths.headsDir.resolve(key),
                    ),
                    watchdog = watchdog,
                    loginCommand = ctx.faultPlan.loginCommand,
                ),
                reasoning = ReasoningSettings(cfg.showReasoning, cfg.replayReasoning, cfg.effort, cfg.summary),
                quirks = quirksOverlay.responsesQuirks(providerCfg, CodexQuirks().defaultQuirks(), cfg),
                // Reasoning-continuation folding (codex 518n-2) — codex head ONLY; grok/openai
                // never receive a fold config, so they stay pure passthrough.
                foldConfig = quirksOverlay.foldConfigFrom(cfg),
                accountIdHeader = providerCfg.quirks.accountIdHeader,
                codeMode = CodexCodeModeWiring(
                    bridge = codeModeBridge(ctx),
                    models = ctx.providerCfg.quirks.codeModeModels,
                    onlyModels = backendCodeModeOnly(ctx),
                ),
            ),
            auth,
            accounts,
        )
    }

    /** V4-441: the models the backend marks `code_mode_only`, read from the head's roster at each turn so a
     *  refresh while the daemon runs reaches the next one. With none known at start (no answer and no kept
     *  list) only `code_mode_models` runs code mode, and the head's log says so once. */
    private fun backendCodeModeOnly(ctx: ProviderBuild): CodeModeOnlyModels {
        val port = CodeModeOnlyModels {
            ctx.roster.discovered.forHead(ctx.key).filter { it.codeModeOnly }.flatMap { it.spellings }
        }
        if (port.ids().isEmpty()) {
            HeadScopedLogs.headScopedLog(ctx.key, log).invoke(
                "[code-mode] no model is marked code_mode_only by the backend's list (none was discovered or " +
                    "kept), so only code_mode_models runs code mode\n",
            )
        }
        return port
    }

    private fun codexAccounts(ctx: ProviderBuild, primaryPath: Path): List<WiredAccount> {
        // Refresh hits the OAuth ISSUER's token endpoint (auth.openai.com), not the API base_url.
        val tokenUrl = CodexOAuthEndpoints.tokenUrl(System::getenv)
        return accountFiles.discover(
            AuthKind.ChatgptOAuth,
            primaryPath,
            HeadScopedLogs.headScopedLog(ctx.key, log),
        ).map { file ->
            WiredAccount(
                label = file.label,
                primary = file.primary,
                auth = codexAuth(ctx, file.credentialFile, tokenUrl),
                quota = WiredAccountQuota(file = file.quotaFile),
                credential = WiredAccountCredential(present = file.credentialPresent, refusal = file.refusal),
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
                    runtimes = {
                        JvmCodeModeRuntime(
                            // TOML quirks overlay the native pool's defaults, including its head memory reservation.
                            limits = PoolLimits(
                                maxWorkers = ctx.providerCfg.quirks.codeModeWorkers ?: DEFAULT_MAX_WORKERS,
                                memoryBudgetMb = ctx.providerCfg.quirks.codeModeMemoryMb ?: DEFAULT_POOL_MEMORY_MB,
                            ),
                            advanceTimeoutMs = ctx.providerCfg.quirks.codeModeTimeoutMs ?: DEFAULT_ADVANCE_TIMEOUT_MS,
                            launch = HostLaunch(heapMb = ctx.providerCfg.quirks.codeModeHeapMb ?: DEFAULT_HEAP_MB),
                        ).also { it.observeHostLifecycle(HeadScopedLogs.headScopedLog(ctx.key, log)) }
                    },
                    state = CodeModeStateLocation(
                        dir = statePaths.headsDir.resolve(ctx.key).resolve(CODE_MODE_DIR),
                        legacyFile = statePaths.stateDir.resolve("${ctx.key}$CODE_MODE_STATE_SUFFIX"),
                    ),
                    log = HeadScopedLogs.headScopedLog(ctx.key, log),
                    cellLease = CodeModeCellLease(sessionAlive = sessionAlive),
                ),
            ).also { bridge ->
                checkNotNull(probeScope.coroutineContext[Job]).invokeOnCompletion {
                    Cancellables.runCatchingBestEffort { bridge.onProviderStop() }.exceptionOrNull()?.let { failure ->
                        HeadScopedLogs.headScopedLog(ctx.key, log).invoke(
                            "[code-mode] terminal save needs its retained retry owner " +
                                "(${SafeFailureText.render(failure)})",
                        )
                    }
                }
            }
        } else {
            null
        }
}
