// NEW: Codex owns its Responses path: ChatGPT OAuth accounts, token refresh, and code-mode bridge.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.dialect.responses.stream.LiveFoldConfig
import splice.oauth.OAuthAccountFiles
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.provider.codex.auth.CodexAuthProvider
import splice.provider.codex.auth.CodexOAuthEndpoints
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
    /** Code mode's half of the arm: each head's bridge and model lists, and the daemon's one liveness poller. */
    private val codeMode: CodexCodeModeAssembly = CodexCodeModeAssembly(statePaths, probeScope, log),
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
                        headers = providerCfg.staticHeaders,
                    ),
                    watchdog = watchdog,
                    loginCommand = ctx.faultPlan.loginCommand,
                ),
                reasoning = ReasoningWiring.settingsOf(cfg),
                quirks = quirksOverlay.responsesQuirks(providerCfg, CodexQuirks().defaultQuirks(), cfg),
                // Reasoning-continuation folding (codex 518n-2) — codex head ONLY; grok/openai
                // never receive a fold config, so they stay pure passthrough. Asked per turn, off this head's
                // config as it stands then, so all four fold knobs are live.
                foldConfig = LiveFoldConfig { quirksOverlay.foldConfigFrom(cfg.current()) },
                // codex-rs sends it on every request of a ChatGPT sign-in; only a config that turns it off keeps it off.
                accountIdHeader = providerCfg.quirks.accountIdHeader ?: true,
                codeMode = codeMode.wiring(ctx),
            ),
            auth,
            accounts,
        )
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
}
