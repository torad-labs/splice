// NEW: the daemon assembly (P4-SUP) — one JVM hosting the control plane + every enabled head.
// Builds each head from topology (provider wired to its dialect + auth + stores), starts control
// :3096 and each head port. suspend all the way (the runBlocking bridge lives in Main); version
// handshake = /health version string equality (a daemon bump restarts all heads together — the
// documented change).
package splice.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import splice.control.ControlServer
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.control.ManagedHead
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.SpliceConfig
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.model.ModelCatalog
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.ToolSurfaceConfig
import splice.core.topology.Topology
import splice.core.topology.catalogFor
import splice.core.topology.configOverrides
import splice.core.topology.effectiveApiKeyEnv
import splice.core.turn.WatchdogBudget
import splice.core.util.discard
import splice.core.util.runCatchingCancellable
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.withReasoningEffortToml
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ToolDeferralPolicy
import splice.dialect.responses.withReasoningCacheToml
import splice.dialect.responses.withToml
import splice.dialect.responses.withToolSurfaceToml
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.head.RequestMaterializationGate
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.CodexProvider
import splice.provider.codex.RefreshedTokens
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokProvider
import splice.provider.kimi.KimiAuthProvider
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuthEndpoints
import splice.provider.kimi.KimiProvider
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.provider.openai.OpenAiResponsesProvider
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds

// Reasoning-continuation folding config (codex 518n-2), threaded from ConfigService like the other
// reasoning knobs. Top-level (off Daemon's function count); an empty model set = feature off.
private fun foldConfigFrom(cfg: SpliceConfig): FoldConfig = FoldConfig(
    models = cfg.foldReasoningModels,
    maxContinue = cfg.foldMaxContinue,
    markerText = cfg.foldMarkerText.ifEmpty { FoldConfig.DEFAULT_MARKER_TEXT },
    maxTierN = cfg.foldMaxTier,
)

internal const val CHATGPT_OAUTH = "chatgpt-oauth"
internal const val GROK_OAUTH = "grok-oauth"
internal const val KIMI_OAUTH = "kimi-oauth"

// The whole head-stop phase's deadline (see [stopHeads]). Kept below Main's STOP_DEADLINE_MS so the
// graceful stop + control shutdown finish before Main's hard halt watchdog would ever need to fire.
private const val HEAD_STOP_BUDGET_MS = 6_000L

/**
 * Best-effort isolation at daemon/head boundaries without turning cancellation or fatal JVM
 * failures into a merely degraded head. Expected I/O and assembly failures become [Result]
 * failures; cancellation and [Error] always escape.
 */
internal inline fun <T> runCatchingDaemonBoundary(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: IOException) {
    Result.failure(failure)
} catch (failure: IllegalArgumentException) {
    Result.failure(failure)
} catch (failure: IllegalStateException) {
    Result.failure(failure)
}

private fun assembleDaemonHeads(
    topology: Topology,
    heads: MutableMap<String, ManagedHead>,
    log: (String) -> Unit,
    assemble: (String, HeadConfig, ProviderConfig) -> ManagedHead,
): LinkedHashMap<String, String> {
    val failed = LinkedHashMap<String, String>()
    for ((key, head) in topology.heads) {
        val providerCfg = topology.providers[head.provider]
        if (providerCfg == null) {
            failed[key] = "unknown provider '${head.provider}'"
            log("[daemon] head '$key' SKIPPED: unknown provider '${head.provider}'\n")
            continue
        }
        runCatchingDaemonBoundary { assemble(key, head, providerCfg) }
            .onSuccess { heads[key] = it }
            .onFailure {
                failed[key] = it.message ?: it.javaClass.simpleName
                log("[daemon] head '$key' SKIPPED (build failed): ${it.message}\n")
            }
    }
    return failed
}

private suspend fun startDaemonHeads(
    heads: Map<String, ManagedHead>,
    failed: MutableMap<String, String>,
    probeScope: CoroutineScope,
    log: (String) -> Unit,
    authProbes: MutableMap<String, AuthProbeLoop>,
) {
    heads.forEach { (key, managed) ->
        runCatchingDaemonBoundary { managed.head.start() }.onFailure {
            failed[key] = "start failed: ${it.message}"
            log("[daemon] head '$key' failed to start: ${it.message}\n")
        }
        startAuthProbeIfRefreshable(key, managed.auth, probeScope, log, authProbes)
    }
}

// The daemon shutdown's head-stop phase, extracted so DaemonStopDeadlineTest can prove the two
// invariants a wedged head must not break. (1) PARALLELISM: the N blocking HeadServer.stop() engine
// stops run CONCURRENTLY on Dispatchers.IO instead of serializing on Main's single-thread runBlocking
// event loop — the "PARALLEL" the old comment claimed but the missing dispatcher silently defeated (a
// blocking server.stop() monopolized the sole thread, compounding shutdown to ~5s x N heads). (2)
// DEADLINE: withTimeoutOrNull caps the whole phase at [budgetMs] so a head whose drain never converges
// cannot extend shutdown unboundedly, and [stopControl] still runs afterward even when the cap trips.
// A truly-uninterruptible thread is beyond this budget's reach — Main's halt watchdog is that guarantee.
internal suspend fun stopHeads(
    heads: Collection<Head>,
    budgetMs: Long,
    log: (String) -> Unit,
    stopControl: () -> Unit,
) {
    val stopFailureHandler = CoroutineExceptionHandler { _, e ->
        log("[daemon] head stop failed uncaught: ${e::class.simpleName}: ${e.message}\n")
    }
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) {
            supervisorScope {
                heads.forEach { head ->
                    launch(stopFailureHandler) {
                        runCatchingDaemonBoundary { head.stop() }
                            .discard("shutdown: one head failing to stop must not block the rest")
                    }
                }
            }
        }
    }
    stopControl()
}

private fun resolveHeadConfig(
    key: String,
    head: HeadConfig,
    provider: ProviderConfig,
    cfg: SpliceConfig,
): HeadConfig = when {
    provider.auth.kind == CHATGPT_OAUTH -> head.copy(port = cfg.port, pinnedModel = cfg.pinnedModel)
    provider.auth.kind == GROK_OAUTH || key.contains("grok", ignoreCase = true) ->
        head.copy(port = cfg.grokPort, pinnedModel = cfg.grokModel)
    else -> head
}

private fun resolveProviderConfig(key: String, provider: ProviderConfig, cfg: SpliceConfig): ProviderConfig =
    when {
        provider.auth.kind == CHATGPT_OAUTH -> provider.copy(baseUrl = cfg.chatgptApiBase)
        provider.auth.kind == GROK_OAUTH || key.contains("grok", ignoreCase = true) ->
            provider.copy(baseUrl = cfg.xaiApiBase)
        else -> provider
    }

/** Overlay the head's TOML [providers.*.quirks] onto a chat-dialect provider's base quirk profile.
 *  Top-level (not a Daemon member): the class sits at detekt's TooManyFunctions ceiling. */
private fun ProviderConfig.chatQuirks(base: ChatQuirks): ChatQuirks =
    base.withReasoningEffortToml(quirks.reasoningEffort)

/** TOML table -> dialect policy. Null (absent table, enabled=false, or the daemon-wide kill
 *  switch) = feature off. The mapping lives HERE, at the assembly point, so the dialect never
 *  imports a topology config type — the same reason withToml takes primitives. Top-level (not a
 *  Daemon member): the class sits at detekt's TooManyFunctions ceiling.
 *
 *  Clamped (review 2026-07-24): ToolSurfaceConfig does no validation of its own, so an operator
 *  TOML typo (e.g. `search_limit = 0`) reached `coerceIn(1, policy.searchLimit)` in
 *  ResponsesToolSearchController unclamped and THREW — a client-visible failed turn on every
 *  round that searched, the one place this feature's own NEVER-BELOW-STATUS-QUO law broke.
 *  Clamping here (like every other numeric knob — ConfigService.normalize) makes a bad value
 *  un-armable instead of a live crash. */
private fun toolDeferralPolicy(t: ToolSurfaceConfig?, globalOff: Boolean): ToolDeferralPolicy? = when {
    t == null -> null
    !t.enabled -> null
    globalOff -> null
    else -> ToolDeferralPolicy(
        deferPrefixes = t.deferPrefixes,
        defer = t.defer.toSet(),
        eager = t.eager.toSet(),
        minDeferred = t.minDeferred.coerceAtLeast(MIN_TOOL_SURFACE_FLOOR),
        searchLimit = t.searchLimit.coerceIn(MIN_TOOL_SURFACE_FLOOR, MAX_TOOL_SEARCH_LIMIT),
        searchRounds = t.searchRounds.coerceIn(MIN_TOOL_SURFACE_FLOOR, MAX_TOOL_SEARCH_ROUNDS),
    )
}

private const val MIN_TOOL_SURFACE_FLOOR = 1
private const val MAX_TOOL_SEARCH_LIMIT = 50
private const val MAX_TOOL_SEARCH_ROUNDS = 5

public class Daemon(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit = { System.err.print(it) },
    private val shutdownDaemon: () -> Unit = {},
    private val refreshCall: suspend (tokenUrl: String, refreshToken: String) -> RefreshAttempt<RefreshedTokens> =
        ::codexRefresh,
) {
    // Topology TOML ([daemon] + [defaults]) feeds the headOverrides layer so reasoning
    // display is operator-editable without recompiling. Env and runtime PATCH still win.
    // [heads.<key>.overrides] rides the per-head layer: heads share ONE ConfigService (one JVM,
    // unlike the Node lineage's process-per-head), so without this a knob tuned for one upstream
    // hit all of them — e.g. kimi's 40-min upstreamTimeoutMs also gave codex a 40-min ceiling.
    private val config = ConfigService(
        statePaths,
        headOverrides = topology.configOverrides(),
        perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
    )
    private val mgmtKey = MgmtKey(statePaths)
    private val requestMaterializationGate = RequestMaterializationGate()

    // set once in start(); the daemon is not usable before it
    private var control: ControlServer? = null
    private val heads = LinkedHashMap<String, ManagedHead>()
    private val stopLock = Mutex()
    private var stopped = false

    // G8: per-head auth/health probe. SupervisorJob so one head's probe failure can't cancel
    // another's — same isolation shape as SingleFlight.kt:33-36.
    private val probeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val authProbes = LinkedHashMap<String, AuthProbeLoop>()

    public suspend fun start() {
        val cfg = config.getConfig()
        // TOML feeds ConfigService's topology layer; state/env/runtime override it consistently.
        // Resolved before the head loop so every launch recipe points at the actual listener.
        val controlPort = cfg.controlPort
        // PER-HEAD BOOT ISOLATION (audit 2026-07-18): one head that fails to assemble (a valid
        // TOML the builder can't wire, e.g. a not-yet-supported dialect) must NOT abort the whole
        // daemon with a stack trace to /dev/null. Log the degraded head and serve the rest.
        val failed = assembleDaemonHeads(topology, heads, log) { key, head, providerCfg ->
            assembleHead(providerContext(key, head, providerCfg), controlPort)
        }
        val srv = ControlServer(
            controlPort,
            heads,
            config,
            mgmtKey,
            dashboardHtml,
            log,
            LaunchService(ClaudeConfigMaterializer(statePaths.rootDir.parent ?: statePaths.rootDir)),
            shutdownDaemon,
            // `failed` fills during assembleDaemonHeads above and again in startDaemonHeads below
            // (a head that assembles fine but fails to start); captured by reference, so this
            // reads live rather than a stale snapshot taken before startDaemonHeads runs.
            { failed.size },
            // Configured total so readyHeads + failedHeads == heads holds even when a head fails to
            // ASSEMBLE (it never enters `heads`) — review 2026-07-23.
            topology.heads.size,
        )
        control = srv
        // Start heads BEFORE opening the control plane so a launch-shim that sees /health and
        // immediately POSTs /launch/<head> does not race a still-binding head (503 head is not running).
        startDaemonHeads(heads, failed, probeScope, log, authProbes)
        // Defense in depth for the restart-into-a-still-bound-port race (BS-4 DEFECT B): unlike the
        // per-head starts above, an uncaught EADDRINUSE here (a prior daemon that freed the lock but
        // not yet the control port) would crash the new daemon to /dev/null, leaving zero serving.
        // Exit cleanly instead — Main's finally stops the heads we started and releases the lock.
        val controlBound = runCatchingDaemonBoundary { srv.start() }
            .onFailure {
                log("[daemon] control plane could not bind :$controlPort (${it.message}); another owns it, exiting\n")
                shutdownDaemon()
            }
            .isSuccess
        if (!controlBound) return
        val degraded = if (failed.isEmpty()) "" else " DEGRADED=${failed.keys}"
        log("[daemon] up: control :$controlPort, heads ${heads.keys}$degraded\n")
    }

    public suspend fun stop(): Unit = stopLock.withLock {
        if (!stopped) {
            stopped = true
            authProbes.values.forEach { it.stop() }
            probeScope.cancel()

            // Heads stop in PARALLEL under a phase DEADLINE, then control stops — see [stopHeads].
            // The supervisor scope + stopFailureHandler live there so an exception escaping one
            // head's stop (a type outside runCatchingDaemonBoundary's list) can't cancel the
            // siblings' drains/flushes nor skip control.stop — it surfaces on stderr/daemon.log
            // instead of the JVM default, a black hole once production redirects stderr to /dev/null.
            stopHeads(heads.values.map { it.head }, HEAD_STOP_BUDGET_MS, log) { control?.stop() }
        }
    }

    /** Provider + its auth, chosen by (dialect, auth.kind) — the multi-provider dispatch. */
    private data class Wired(val provider: Provider, val auth: RefreshableAuthProvider)

    /** Resolve one head's build inputs against ITS OWN effective config. Heads share a single
     *  ConfigService (one JVM), so every value here must come from `getConfig(key)` — reading the
     *  global view is what made a knob tuned for one upstream govern all of them. */
    private fun providerContext(key: String, head: HeadConfig, providerCfg: ProviderConfig): ProviderBuild {
        val headCfg = config.getConfig(key)
        val resolvedHead = resolveHeadConfig(key, head, providerCfg, headCfg)
        val resolvedProvider = resolveProviderConfig(key, providerCfg, headCfg)
        return ProviderBuild(
            key = key,
            head = resolvedHead,
            providerCfg = resolvedProvider,
            catalog = resolvedProvider.catalogFor(resolvedHead, headCfg.contextWindowOverride),
            watchdog = WatchdogBudget(
                firstByteTimeout = headCfg.firstByteTimeoutMs.milliseconds,
                streamIdle = headCfg.streamIdleMs.milliseconds,
                totalCap = headCfg.upstreamTimeoutMs.milliseconds,
            ),
            cfg = headCfg,
            loginCommand = signInPlan(resolvedProvider, resolvedHead, key).command,
        )
    }

    /** The per-head inputs every provider builder threads through — a parameter object. */
    private data class ProviderBuild(
        val key: String,
        val head: HeadConfig,
        val providerCfg: ProviderConfig,
        val catalog: ModelCatalog,
        val watchdog: WatchdogBudget,
        val cfg: SpliceConfig,
        val loginCommand: String,
    )

    // The dispatch that makes the daemon genuinely multi-provider: codex (responses+oauth), grok
    // (responses or chat + grok-oauth), openai-platform (responses+api-key, hash cache key),
    // and ANY openai-compatible vendor (chat dialect + api-key) — the last is pure TOML, zero code.
    private fun buildProvider(ctx: ProviderBuild): Wired {
        val label = ctx.head.claude.command ?: ctx.key
        return when (ctx.providerCfg.dialect) {
            Dialect.OPENAI_RESPONSES -> responsesProvider(ctx, label)
            Dialect.OPENAI_CHAT -> chatProvider(ctx, label)
            // anthropic-passthrough: kimi (device-oauth, x-api-key) or ANY anthropic-compatible
            // vendor (api-key Bearer, e.g. Moonshot's pay-per-token https://api.moonshot.ai/anthropic).
            Dialect.ANTHROPIC_PASSTHROUGH -> passthroughProvider(ctx, label)
        }
    }

    // openai-chat dispatch: grok-oauth (SuperGrok Bearer + refresh, same auth as the Responses
    // path) vs any api-key vendor. grok rides this dialect because xAI's /v1/chat/completions
    // streams the full readable CoT (`reasoning_content`) where the Responses summary channel
    // stops mid-reasoning (measured 2026-07-18; grok CLI / OpenCode parity).
    private fun chatProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val auth = when (providerCfg.auth.kind) {
            GROK_OAUTH -> {
                val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
                GrokAuthProvider(
                    authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.grok/auth.json")),
                    authCacheMs = ctx.cfg.authCacheMs,
                    refreshCall = { rt -> grokRefresh(tokenUrl, rt) },
                    prefetchScope = probeScope,
                )
            }
            else -> ApiKeyAuthProvider(
                envVar = effectiveApiKeyEnv(key, providerCfg.auth),
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
                quirks = providerCfg.chatQuirks(
                    if (providerCfg.auth.kind == GROK_OAUTH) {
                        ChatQuirks(providerTag = key, sessionCacheKeyPrefix = label, emitUsageInStream = true)
                    } else {
                        ChatQuirks(providerTag = key)
                    },
                ),
                showReasoning = ctx.cfg.showReasoning,
            ),
            auth,
        )
    }

    // anthropic-passthrough dispatch: kimi-oauth (device flow, x-api-key, proactive refresh) vs any
    // other kind (ApiKeyAuthProvider → Bearer, correct for Moonshot's anthropic pay-per-token base).
    // Both instantiate the SAME KimiProvider — the passthrough dialect and X-Msh-* identity headers
    // are shared; only the auth differs.
    private fun passthroughProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        val cfg = ctx.cfg
        return when (providerCfg.auth.kind) {
            KIMI_OAUTH -> {
                val authPath = Paths.get(
                    TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.kimi/credentials/kimi-code.json"),
                )
                val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
                val identityHeaders = identity.headers()
                val tokenUrl = KimiOAuthEndpoints.tokenUrl(System::getenv)
                val auth = KimiAuthProvider(
                    authPath = authPath,
                    authCacheMs = cfg.authCacheMs,
                    refreshCall = { rt -> kimiRefresh(tokenUrl, rt, identityHeaders) },
                    prefetchScope = probeScope,
                )
                Wired(kimiProvider(ctx, label, auth, identity), auth)
            }
            else -> {
                val auth = ApiKeyAuthProvider(
                    envVar = effectiveApiKeyEnv(key, providerCfg.auth),
                    keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
                )
                val identity = KimiDeviceIdentity(deviceIdPath = statePaths.stateDir.resolve("$key-device_id"))
                Wired(kimiProvider(ctx, label, auth, identity), auth)
            }
        }
    }

    private fun kimiProvider(
        ctx: ProviderBuild,
        label: String,
        auth: RefreshableAuthProvider,
        identity: KimiDeviceIdentity,
    ): Provider = KimiProvider(
        tuning = ProviderTuning(
            key = ctx.key,
            label = label,
            catalog = ctx.catalog,
            pinnedModel = ctx.head.pinnedModel,
            auth = auth,
            baseUrl = ctx.providerCfg.baseUrl,
            watchdog = ctx.watchdog,
            loginCommand = ctx.loginCommand,
        ),
        identity = identity,
    )

    /** Overlay the head's TOML [providers.*.quirks] onto a provider's base quirk profile. */
    // quirks.effortCeiling is intentionally not passed: the effort ladder clamps per provider.
    private fun ProviderConfig.responsesQuirks(
        base: ResponsesQuirks,
        cfg: SpliceConfig,
    ): ResponsesQuirks = base.withToml(
        store = quirks.store,
        cacheKey = quirks.cacheKey,
        summaryField = quirks.summaryField,
        compactEffort = quirks.compactEffort,
        toolChoice = quirks.toolChoice,
    ).withReasoningCacheToml(quirks.reasoningCache)
        .withToolSurfaceToml(toolDeferralPolicy(quirks.toolSurface, cfg.toolSurfaceOff))

    private fun responsesProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        return when (providerCfg.auth.kind) {
            CHATGPT_OAUTH -> {
                // Refresh hits the OAuth ISSUER's token endpoint (auth.openai.com), not the API base_url.
                val tokenUrl = CodexOAuthEndpoints.tokenUrl(System::getenv)
                val auth = CodexAuthProvider(
                    authPath = Paths.get(TopologyLoader.expandHome(cfg.codexAuthPath)),
                    authCacheMs = cfg.authCacheMs,
                    refreshCall = { rt -> refreshCall(tokenUrl, rt) },
                    prefetchScope = probeScope,
                )
                Wired(
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
                        quirks = providerCfg.responsesQuirks(CodexProvider.defaultQuirks(), cfg),
                        // Reasoning-continuation folding (codex 518n-2) — codex head ONLY; grok/openai
                        // never receive a fold config, so they stay pure passthrough.
                        foldConfig = foldConfigFrom(cfg),
                        accountIdHeader = providerCfg.quirks.accountIdHeader,
                    ),
                    auth,
                )
            }
            GROK_OAUTH -> grokOAuthProvider(ctx, label)
            else -> apiKeyResponsesProvider(ctx, label)
        }
    }

    // grok via the SuperGrok/X-Premium+ browser OAuth (~/.grok/auth.json, Bearer + refresh) — the
    // same Responses dialect + grok quirks, only the auth differs from the api-key path.
    private fun grokOAuthProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
        val auth = GrokAuthProvider(
            authPath = Paths.get(TopologyLoader.expandHome(cfg.grokAuthPath)),
            authCacheMs = cfg.authCacheMs,
            refreshCall = { rt -> grokRefresh(tokenUrl, rt) },
            prefetchScope = probeScope,
        )
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
                quirks = providerCfg.responsesQuirks(GrokProvider.defaultQuirks(), cfg),
            ),
            auth,
        )
    }

    // api-key + responses: grok (session-id cache key) vs openai platform (first-message-hash).
    // Reasoning display knobs come from ConfigService (TOML [daemon] / env / state).
    private fun apiKeyResponsesProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        val auth = ApiKeyAuthProvider(
            envVar = effectiveApiKeyEnv(key, providerCfg.auth),
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
                quirks = providerCfg.responsesQuirks(GrokProvider.defaultQuirks(), cfg),
            )
        } else {
            OpenAiResponsesProvider(
                tuning = tuning,
                showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning,
                configEffort = cfg.effort,
                configSummary = cfg.summary,
                quirks = providerCfg.responsesQuirks(OpenAiResponsesProvider.defaultQuirks(), cfg),
            )
        }
        return Wired(provider, auth)
    }

    // The /model picker option list Claude Code caches in .claude.json — every model with its
    // label, description, and window, so all of them appear in the picker (not just the pinned one).
    private fun modelOptionsCache(providerCfg: ProviderConfig): JsonElement = buildJsonArray {
        providerCfg.models.forEach { model ->
            addJsonObject {
                put("value", model.id)
                put("label", model.label.ifEmpty { model.id })
                put("description", model.description.ifEmpty { model.label.ifEmpty { model.id } })
                put("context_window", model.contextWindow)
            }
        }
    }

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    // The sign-in plan (OAuth browser flow vs api-key masked prompt + token capture) lives in
    // SignInPlan.kt — factored out of this class (detekt LargeClass).

    private fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val head = ctx.head
        val cfg = ctx.cfg
        val wired = buildProvider(ctx)
        val usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key))
        val compactStats = CompactStats(statePaths.compactStatsFile(key))
        val perfStats = PerfStats(statePaths.perfStatsFile(key))
        val logFile = statePaths.logsDir.resolve("daemon.log")
        val server = HeadServer(
            provider = wired.provider,
            listenPort = head.port,
            deps = HeadDeps(
                upstream = UpstreamClient(
                    cfg.firstByteTimeoutMs,
                    cfg.upstreamTimeoutMs,
                    cfg.upstreamRetries,
                    client = UpstreamClient.defaultClient(cfg.firstByteTimeoutMs, cfg.upstreamTimeoutMs, log),
                ),
                inferenceToken = mgmtKey.get(),
                // Re-read per head on EVERY admission (still hot-resizable): the ceiling belongs to
                // the upstream ACCOUNT, not the gateway. One shared value meant a workflow fan-out
                // admitted 100 concurrent streams into a single account, 429'd, and armed the shared
                // cooldown — measured 67% turn failure at inflight=100 vs 0.3% at <=14 (perf jsonl,
                // 2026-07-24). Per-head lets a slow upstream sit low while a fast one stays high.
                gate = InflightGate(
                    maxInflight = { config.getConfig(key).maxInflight },
                    maxQueued = { config.getConfig(key).maxQueued },
                ),
                shadow = ShadowClassifier(log = log),
                compactStats = compactStats,
                mirrorReasoning = cfg.mirrorReasoning,
                usageStore = usageStore,
                perfStats = perfStats,
                log = log,
                requestMaterializationGate = requestMaterializationGate,
            ),
        )
        val apiKeyPresent = (wired.auth as? ApiKeyAuthProvider)?.hasKeyNow() != false
        return ManagedHead(
            head = server,
            auth = wired.auth,
            usage = UsageStoreSource(usageStore),
            compact = CompactStatsSource(compactStats),
            logs = LogFileSource(logFile, "[$key]"),
            warnPct = cfg.usageWarnPct,
            warnTokens5h = cfg.usageWarnTokens5h,
            authKind = ctx.providerCfg.auth.kind,
            launchSpec = launchSpecFor(ctx, controlPort, keyPresent = apiKeyPresent),
            perf = PerfStatsSource(perfStats),
        )
    }

    private fun launchSpecFor(ctx: ProviderBuild, controlPort: Int, keyPresent: Boolean): LaunchSpec {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val configDir = Paths.get(TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"))
        val signIn = signInPlan(providerCfg, head, key)
        return LaunchSpec(
            configDir = configDir,
            pinnedModel = head.pinnedModel,
            availableModelIds = ctx.catalog.availableModelIds(),
            modelLabels = providerCfg.models.associate { it.id to it.label.ifEmpty { it.id } },
            contextWindow = ctx.catalog.contextWindowFor(head.pinnedModel).toInt(),
            modelOptionsCache = modelOptionsCache(providerCfg),
            statuslineCommand = "curl -sS --data-binary @- http://127.0.0.1:$controlPort/statusline/$key",
            // The installed wrapper (`<command> login`) runs this head's provider sign-in; the
            // materialized /login command + UserPromptSubmit hook route the user here. api-key
            // heads additionally capture a bare pasted token, and advertise the flow at session
            // start ONLY while the key is unconfigured (re-materialized each launch).
            loginCommand = signIn.command,
            signInLabel = signIn.label,
            signInViaBrowser = signIn.viaBrowser,
            tokenCapture = signIn.tokenCapture,
            advertiseKeySetup = signIn.tokenCapture != null && !keyPresent,
            policy = ClaudePolicy(share = topology.claude.share.toSet(), isolate = head.claude.isolate.toSet()),
            port = head.port,
            inferenceToken = mgmtKey.get(),
        )
    }

    public companion object {
        public fun dashboardFrom(
            distPath: Path,
            classpathHtml: () -> String? = {
                Daemon::class.java.getResourceAsStream("/webui/index.html")
                    ?.bufferedReader()
                    ?.use { it.readText() }
            },
        ): () -> String = {
            runCatchingCancellable { Files.readString(distPath) }
                .getOrNull()
                ?: runCatchingCancellable { classpathHtml() }.getOrNull()
                ?: "<!doctype html><title>splice</title><p>dashboard build missing</p>"
        }
    }
}
