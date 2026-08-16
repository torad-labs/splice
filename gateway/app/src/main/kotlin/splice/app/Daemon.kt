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
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.SpliceConfig
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.LoginOutcomeFile
import splice.core.model.ModelCatalog
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.ToolSurfaceConfig
import splice.core.topology.Topology
import splice.core.topology.catalogFor
import splice.core.topology.configOverrides
import splice.core.topology.effectiveApiKeyEnv
import splice.core.topology.invalidPortMessage
import splice.core.topology.portCollisionMessage
import splice.core.turn.WatchdogBudget
import splice.core.util.discard
import splice.core.util.headScopedLog
import splice.core.util.runCatchingCancellable
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.withReasoningEffortToml
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ToolDeferralPolicy
import splice.dialect.responses.withParallelToolCallsToml
import splice.dialect.responses.withReasoningCacheToml
import splice.dialect.responses.withToml
import splice.dialect.responses.withToolSurfaceToml
import splice.dialect.responses.withWebSocketToml
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

/** The head forwards the CALLER's own auth and holds none itself (campaign claude-head). */
internal const val CLIENT = ClientAuthProvider.KIND

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
    statePaths: StatePaths,
    heads: MutableMap<String, ManagedHead>,
    log: (String) -> Unit,
    assemble: (String, HeadConfig, ProviderConfig) -> ManagedHead,
): LinkedHashMap<String, String> {
    val failed = LinkedHashMap<String, String>()
    // CTL-005: name an out-of-range port before the head hits an opaque bind-time error.
    val invalidPorts = topology.invalidPortHeads()
    for ((key, port) in invalidPorts) {
        failed[key] = invalidPortMessage(key, port)
        log("[daemon][boot] ${invalidPortMessage(key, port)}\n")
    }
    // JW-13: name a duplicate-port collision before the loser hits an opaque "Address already in
    // use". Both colliding heads are marked failed with a message pointing at the sibling.
    val portDupes = topology.portCollisions()
    val collidingHeads = portDupes.values.flatten().toSet()
    for ((port, keys) in portDupes) {
        keys.forEach { failed[it] = portCollisionMessage(port, keys) }
        log("[daemon][boot] ${portCollisionMessage(port, keys)}\n")
    }
    // Invalid-port and colliding heads already failed above with a named reason — filter them
    // out so the assembly loop keeps a single continue (detekt LoopWithTooManyJumpStatements).
    for ((key, head) in topology.heads.filterKeys { it !in collidingHeads && it !in invalidPorts.keys }) {
        val providerCfg = topology.providers[head.provider]
        if (providerCfg == null) {
            failed[key] = "unknown provider '${head.provider}'"
            log("[$key][boot] SKIPPED: unknown provider '${head.provider}'\n")
            continue
        }
        runCatchingDaemonBoundary { assemble(key, head, providerCfg) }
            .onSuccess { heads[key] = it }
            .onFailure {
                failed[key] = it.message ?: it.javaClass.simpleName
                log("[$key][boot] SKIPPED (build failed): ${it.message}\n")
            }
    }
    // IO-006: heads above that alias to the same legacy usage file (deliberate migration
    // continuity, not a bug on its own — see [logUsageKeyCollisions]) share it with no
    // cross-process coordination if both run at once. Split off the file's CyclomaticComplexMethod
    // ceiling, same reason [portCollisionMessage] et al. already live top-level.
    logUsageKeyCollisions(statePaths, heads.keys, log)
    return failed
}

/** IO-006: neither head is refused (that would break the codex/claudex usage-history migration
 *  the alias exists for) — the collision is only named, loudly, same idiom as JW-13's
 *  [portCollisionMessage]. */
private fun logUsageKeyCollisions(statePaths: StatePaths, headKeys: Collection<String>, log: (String) -> Unit) {
    statePaths.usageKeyCollisions(headKeys).forEach { (statKey, keys) ->
        log(
            "[daemon][boot] WARNING: heads ${keys.joinToString(" and ")} share the '$statKey' usage/ratelimit " +
                "files with no cross-process write coordination — quota numbers may race\n",
        )
    }
}

/** The two per-head probe sinks startDaemonHeads writes into (detekt LongParameterList). */
internal data class HeadProbeSinks(
    val authProbes: MutableMap<String, AuthProbeLoop>,
    val turnPathStalled: java.util.concurrent.ConcurrentHashMap<String, Boolean>,
)

private suspend fun startDaemonHeads(
    heads: Map<String, ManagedHead>,
    failed: MutableMap<String, String>,
    probeScope: CoroutineScope,
    log: (String) -> Unit,
    sinks: HeadProbeSinks,
) {
    heads.forEach { (key, managed) ->
        runCatchingDaemonBoundary { managed.head.start() }.onFailure {
            failed[key] = "start failed: ${it.message}"
            log("[$key][boot] failed to start: ${it.message}\n")
        }
        startAuthProbeIfRefreshable(key, managed.auth, probeScope, log, sinks.authProbes)
        TurnPathProbeLoop(key, managed.head.port, sinks.turnPathStalled, log).start(probeScope)
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

/** Kimi's static vendor headers as they were hardcoded before the TOML surface existed: its
 *  /coding endpoint 403s an unrecognized UA, and the Anthropic wire needs its version on every
 *  request. Kept as the kimi arms' BASE (the example TOML declares the same values as
 *  documentation) so an operator who never edited splice.toml keeps a working head. */
private val KIMI_BASE_HEADERS = mapOf(
    "anthropic-version" to "2023-06-01",
    "User-Agent" to "KimiCLI/1.5",
)

/** Top-level (not a Daemon member): the class sits at detekt's LargeClass ceiling, and this
 *  helper reads only its arguments — the same reason chatQuirks/toolDeferralPolicy live here. */
/** The dialect's ONE provider, fed DECLARED data: TOML quirks overlaid on the head's base
 *  profile, TOML static headers, and (kimi only) the computed device identity. */
private fun passthroughProviderFor(
    ctx: Daemon.ProviderBuild,
    label: String,
    auth: RefreshableAuthProvider,
    base: PassthroughQuirks,
    baseHeaders: Map<String, String> = emptyMap(),
    identityHeaders: () -> Map<String, String> = { emptyMap() },
): Provider = PassthroughProvider(
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
    quirks = ctx.providerCfg.passthroughQuirks(base),
    // Base FIRST so an operator's TOML overrides it, and absent TOML keeps the head serving: these
    // headers used to be hardcoded in the provider, so a splice.toml written before extra_headers
    // existed would otherwise lose kimi's UA — which its /coding endpoint 403s on.
    staticHeaders = baseHeaders + ctx.providerCfg.staticHeaders,
    identityHeaders = identityHeaders,
    // PT-002/v27: same session-stable effort proxy ResponsesProvider threads as configEffort.
    configEffort = ctx.cfg.effort,
)

/** Overlay the head's TOML [providers.*.quirks] onto a passthrough head's BASE quirk profile.
 *  Absent (null) keeps the base, which is what makes a splice.toml written before these knobs
 *  existed keep serving a kimi head unchanged; an explicitly-set knob wins. Same shape as
 *  [chatQuirks]/[responsesQuirks], and the mapping lives HERE, at the assembly point, so the
 *  dialect never imports a topology config type. */
internal fun ProviderConfig.passthroughQuirks(base: PassthroughQuirks): PassthroughQuirks = base.copy(
    mapThinkingToAdaptive = quirks.mapThinkingAdaptive ?: base.mapThinkingToAdaptive,
    compactEffort = quirks.compactEffort ?: base.compactEffort,
    stripSamplingParams = quirks.stripSamplingParams ?: base.stripSamplingParams,
    mfjsSanitize = quirks.mfjs ?: base.mfjsSanitize,
    // takeIf isNotEmpty: `block_allowlist = []` is the ONLY thing an operator can write to mean
    // "no allowlist" on a head whose base profile has one, and read literally it is an allowlist
    // that permits nothing — every content block of every message dropped, silently, leaving the
    // upstream an empty conversation. Empty means OFF.
    blockAllowlist = quirks.blockAllowlist?.takeIf { it.isNotEmpty() }?.toSet() ?: base.blockAllowlist,
    stripCacheControl = quirks.stripCacheControl ?: base.stripCacheControl,
    synthesizeSignatures = quirks.synthesizeSignatures ?: base.synthesizeSignatures,
)

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
    // JW-04: the booted config identity (sha-256 of the parsed bytes + the resolved path).
    // Defaults keep every existing test constructor compiling; Main always passes both.
    private val topologyDigest: String = "",
    private val topologyPath: Path? = null,
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

    // Turn-path liveness (2026-08-12): key -> stalled. Written by TurnPathProbeLoop, read by
    // /health. The 91h wedge proved head liveness and head CONFIGURATION are different facts.
    private val turnPathStalled = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    public suspend fun start() {
        val cfg = config.getConfig()
        // TOML feeds ConfigService's topology layer; state/env/runtime override it consistently.
        // Resolved before the head loop so every launch recipe points at the actual listener.
        val controlPort = cfg.controlPort
        // PER-HEAD BOOT ISOLATION (audit 2026-07-18): one head that fails to assemble (a valid
        // TOML the builder can't wire, e.g. a not-yet-supported dialect) must NOT abort the whole
        // daemon with a stack trace to /dev/null. Log the degraded head and serve the rest.
        val failed = assembleDaemonHeads(topology, statePaths, heads, log) { key, head, providerCfg ->
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
            topologyDigest = topologyDigest,
            configPath = topologyPath?.toString().orEmpty(),
            topologyStale = topologyStaleProbe(topologyPath, topologyDigest),
            turnPathStalled = { turnPathStalled.filterValues { it }.keys.sorted() },
        )
        control = srv
        // Start heads BEFORE opening the control plane so a launch-shim that sees /health and
        // immediately POSTs /launch/<head> does not race a still-binding head (503 head is not running).
        startDaemonHeads(heads, failed, probeScope, log, HeadProbeSinks(authProbes, turnPathStalled))
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
    // `internal`, not private: DaemonPerHeadConfigTest calls this directly to pin that each head
    // resolves against getConfig(key). No production caller outside this class (2026-07-26 review).
    internal fun providerContext(key: String, head: HeadConfig, providerCfg: ProviderConfig): ProviderBuild {
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
    internal data class ProviderBuild(
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
                    // JW-03: [<headKey>] first, so [grok-auth] refresh lines reach the head's tail
                    log = headScopedLog(ctx.key, log),
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
    // Both build the SAME generic PassthroughProvider; what differs is DATA — the auth, the base
    // quirk profile, and whether a computed device identity rides along.
    //
    // The base profile is what a pre-campaign splice.toml relies on: a kimi-oauth head bases on
    // Kimi's deformation set, so an operator who never declared the new quirks keeps working, while
    // any knob their TOML DOES set still overrides. The api-key arm bases on Kimi's set too, because
    // that arm exists for Moonshot's own anthropic endpoint (the pay-per-token twin of the OAuth
    // head) — an unrelated anthropic-compatible vendor declares what it needs in TOML.
    private fun passthroughProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        // A client-auth head holds NO credential and declares its vendor facts in TOML, so it takes
        // the NEUTRAL base: no Kimi deformations, no Kimi headers, no device identity.
        if (providerCfg.auth.kind == CLIENT) {
            val auth = ClientAuthProvider(key)
            return Wired(
                passthroughProviderFor(ctx, label, auth, PassthroughQuirks(providerTag = key)),
                auth,
            )
        }
        val (auth, identity) = when (providerCfg.auth.kind) {
            KIMI_OAUTH -> kimiOauthAuth(ctx)
            else -> {
                val apiKey = ApiKeyAuthProvider(
                    envVar = effectiveApiKeyEnv(key, providerCfg.auth),
                    keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
                )
                apiKey to KimiDeviceIdentity(deviceIdPath = statePaths.stateDir.resolve("$key-device_id"))
            }
        }
        return Wired(
            passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = PassthroughQuirks.kimi(key),
                baseHeaders = KIMI_BASE_HEADERS,
                identityHeaders = identity::headers,
            ),
            auth,
        )
    }

    /** Kimi's device-flow OAuth: the device identity is built FIRST because its X-Msh-* headers
     *  ride the refresh call itself, then the auth provider that refreshes against them. */
    private fun kimiOauthAuth(ctx: ProviderBuild): Pair<RefreshableAuthProvider, KimiDeviceIdentity> {
        val authPath = Paths.get(
            TopologyLoader.expandHome(ctx.providerCfg.auth.file ?: "~/.kimi/credentials/kimi-code.json"),
        )
        val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
        val identityHeaders = identity.headers()
        val tokenUrl = KimiOAuthEndpoints.tokenUrl(System::getenv)
        val auth = KimiAuthProvider(
            authPath = authPath,
            authCacheMs = ctx.cfg.authCacheMs,
            refreshCall = { rt -> kimiRefresh(tokenUrl, rt, identityHeaders) },
            prefetchScope = probeScope,
            // JW-03: [<headKey>] first, so [kimi-auth] refresh lines reach the head's tail
            log = headScopedLog(ctx.key, log),
        )
        return auth to identity
    }

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
        .withParallelToolCallsToml(quirks.parallelToolCalls)
        .withWebSocketToml(quirks.webSocket)
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
                    // JW-03: [<headKey>] first, so [codex-auth] refresh lines reach the head's tail
                    log = headScopedLog(key, log),
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
            // JW-03: [<headKey>] first, so [grok-auth] refresh lines reach the head's tail
            log = headScopedLog(key, log),
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
                    // CX-03: zstd request bodies — a TOML quirk, absent = plaintext. A quirk and
                    // not a hardcoded provider check for two reasons: the operator opts in per
                    // provider (proven only for ChatGPT, by codex-cli itself; xAI 400d on a
                    // compressed body 2026-07-18), and the migration oracle's scratch topology
                    // carries no quirk, so its 11 byte-exact fixtures replay plaintext — the
                    // hardcoded check compressed the oracle's bodies and crashed its vendored
                    // mock's JSON.parse, which was the source of every leaked harness daemon.
                    zstdRequestBody = ctx.providerCfg.quirks.zstdRequestBody == true,
                    client = UpstreamClient.defaultClient(cfg.firstByteTimeoutMs, cfg.upstreamTimeoutMs, log),
                ),
                inferenceToken = mgmtKey.get(),
                // Only a client-auth head: it holds no splice credential, so the mgmt-key door
                // would reject exactly the requests it exists to serve, and the caller's own auth
                // headers are what ride upstream. Every other head keeps enforcing the key.
                forwardClientAuth = ctx.providerCfg.auth.kind == CLIENT,
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
            // A client-auth head serves ANTHROPIC on the client's own login, so the recipe must not
            // strip its credentials, plant the gateway bearer, or disable /login (campaign claude-head).
            nativeClientAuth = providerCfg.auth.kind == CLIENT,
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
            // ONLY while the key is MISSING (review of #75). The capture hook swallows a bare
            // sk-or-… message and stores it; on a head that is already configured that is pure
            // downside — an accidental paste (or discussing a key as the whole message) silently
            // OVERWRITES a working credential and the message never reaches the model. The
            // advertiser below was already gated this way; the hook that acts on the paste was not.
            tokenCapture = signIn.tokenCapture?.takeIf { !keyPresent },
            // The receipt path MUST match what LoginCommand writes (same StatePaths, same head
            // key), or a detached sign-in reports into a file nothing reads.
            loginOutcomeFile = LoginOutcomeFile.pathFor(StatePaths().stateDir, key).toString(),
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

/** JW-04: per-request staleness recompute, failing OPEN — an unreadable file degrades the
 *  signal, never /health. Top-level: Daemon sits at detekt's LargeClass ceiling. */
private fun topologyStaleProbe(topologyPath: Path?, bootDigest: String): () -> Boolean = {
    val now = topologyPath?.let { TopologyLoader.currentDigest(it) }
    now != null && bootDigest.isNotEmpty() && now != bootDigest
}

/** Pure roster -> dropdown-cache projection. Top-level: Daemon sits at detekt's LargeClass
 *  ceiling (JW-04 relocation; SignInPlan.kt was the same move). */
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
