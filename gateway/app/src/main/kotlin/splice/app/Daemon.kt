// NEW: the daemon assembly (P4-SUP) — one JVM hosting the control plane + every enabled head.
// Builds each head from topology (provider wired to its dialect + auth + stores), starts control
// :3096 and each head port. suspend all the way (the runBlocking bridge lives in Main); version
// handshake = /health version string equality (a daemon bump restarts all heads together — the
// documented change).
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.model.ModelCatalog
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.catalogFor
import splice.core.topology.configOverrides
import splice.core.turn.WatchdogBudget
import splice.core.util.discard
import splice.dialect.chat.ChatQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.withToml
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

public class Daemon(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit = { System.err.print(it) },
    private val refreshCall: suspend (tokenUrl: String, refreshToken: String) -> RefreshAttempt<RefreshedTokens> =
        ::codexRefresh,
) {
    // Topology TOML ([daemon] + [defaults]) feeds the headOverrides layer so reasoning
    // display is operator-editable without recompiling. Env and runtime PATCH still win.
    private val config = ConfigService(statePaths, headOverrides = topology.configOverrides())
    private val mgmtKey = MgmtKey(statePaths)

    // set once in start(); the daemon is not usable before it
    private var control: ControlServer? = null
    private val heads = LinkedHashMap<String, ManagedHead>()

    // G8: per-head auth/health probe. SupervisorJob so one head's probe failure can't cancel
    // another's — same isolation shape as SingleFlight.kt:33-36.
    private val probeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val authProbes = LinkedHashMap<String, AuthProbeLoop>()

    public suspend fun start() {
        val cfg = config.getConfig()
        val watchdog = WatchdogBudget(
            firstByteTimeout = cfg.firstByteTimeoutMs.milliseconds,
            streamIdle = cfg.streamIdleMs.milliseconds,
            totalCap = cfg.upstreamTimeoutMs.milliseconds,
        )
        // topology owns the control port (loaded once at start, restart-required); the
        // ConfigService knob is only the hot-knob default when topology omits it. Resolved before
        // the head loop so each head's launch spec can point its statusline at this port.
        val controlPort = topology.daemon.controlPort?.takeIf { it > 0 } ?: cfg.controlPort
        // PER-HEAD BOOT ISOLATION (audit 2026-07-18): one head that fails to assemble (a valid
        // TOML the builder can't wire, e.g. a not-yet-supported dialect) must NOT abort the whole
        // daemon with a stack trace to /dev/null. Log the degraded head and serve the rest.
        val failed = LinkedHashMap<String, String>()
        for ((key, head) in topology.heads) {
            val providerCfg = topology.providers[head.provider]
            if (providerCfg == null) {
                failed[key] = "unknown provider '${head.provider}'"
                log("[daemon] head '$key' SKIPPED: unknown provider '${head.provider}'\n")
                continue
            }
            runCatching {
                val catalog = providerCfg.catalogFor(head)
                val loginCommand = loginInterception(providerCfg, head, key).first
                val ctx = ProviderBuild(key, head, providerCfg, catalog, watchdog, cfg, loginCommand)
                assembleHead(ctx, controlPort)
            }.onSuccess { heads[key] = it }
                .onFailure {
                    failed[key] = it.message ?: it.javaClass.simpleName
                    log("[daemon] head '$key' SKIPPED (build failed): ${it.message}\n")
                }
        }
        val materializerHome = statePaths.rootDir.parent ?: statePaths.rootDir
        val launchService = LaunchService(ClaudeConfigMaterializer(materializerHome))
        val srv = ControlServer(controlPort, heads, config, mgmtKey, dashboardHtml, log, launchService)
        control = srv
        srv.start()
        // Start each head in isolation too — a listen() failure on one port must not sink the others.
        heads.forEach { (key, m) ->
            runCatching { m.head.start() }.onFailure {
                failed[key] = "start failed: ${it.message}"
                log("[daemon] head '$key' failed to start: ${it.message}\n")
            }
            // Auth probing is orthogonal to port-bind success — probe even a head whose start() failed.
            startAuthProbeIfRefreshable(key, m.auth, probeScope, log, authProbes)
        }
        val degraded = if (failed.isEmpty()) "" else " DEGRADED=${failed.keys}"
        log("[daemon] up: control :$controlPort, heads ${heads.keys}$degraded\n")
    }

    public suspend fun stop() {
        authProbes.values.forEach { it.stop() }
        probeScope.cancel()
        heads.values.forEach {
            runCatching { it.head.stop() }.discard("shutdown: one head failing to stop must not block the rest")
        }
        control?.stop()
    }

    /** Provider + its auth, chosen by (dialect, auth.kind) — the multi-provider dispatch. */
    private data class Wired(val provider: Provider, val auth: RefreshableAuthProvider)

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
            "grok-oauth" -> {
                val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
                GrokAuthProvider(
                    authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.grok/auth.json")),
                    authCacheMs = ctx.cfg.authCacheMs,
                    refreshCall = { rt -> grokRefresh(tokenUrl, rt) },
                )
            }
            else -> ApiKeyAuthProvider(
                envVar = providerCfg.auth.env ?: "${key.uppercase()}_API_KEY",
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
                quirks = ChatQuirks(providerTag = key),
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
            "kimi-oauth" -> {
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
                    envVar = providerCfg.auth.env ?: "${key.uppercase()}_API_KEY",
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
    private fun ProviderConfig.responsesQuirks(base: ResponsesQuirks): ResponsesQuirks = base.withToml(
        store = quirks.store,
        cacheKey = quirks.cacheKey,
        summaryField = quirks.summaryField,
        compactEffort = quirks.compactEffort,
        toolChoice = quirks.toolChoice,
    )

    private fun responsesProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val catalog = ctx.catalog
        val watchdog = ctx.watchdog
        val cfg = ctx.cfg
        return when (providerCfg.auth.kind) {
            "chatgpt-oauth" -> {
                // Refresh hits the OAuth ISSUER's token endpoint (auth.openai.com), not the API base_url.
                val tokenUrl = CodexOAuthEndpoints.tokenUrl(System::getenv)
                val auth = CodexAuthProvider(
                    authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.codexAuthPath)),
                    authCacheMs = cfg.authCacheMs,
                    refreshCall = { rt -> refreshCall(tokenUrl, rt) },
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
                        quirks = providerCfg.responsesQuirks(CodexProvider.defaultQuirks()),
                        accountIdHeader = providerCfg.quirks.accountIdHeader,
                    ),
                    auth,
                )
            }
            "grok-oauth" -> grokOAuthProvider(ctx, label)
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
            authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.grok/auth.json")),
            authCacheMs = cfg.authCacheMs,
            refreshCall = { rt -> grokRefresh(tokenUrl, rt) },
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
                quirks = providerCfg.responsesQuirks(GrokProvider.defaultQuirks()),
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
            envVar = providerCfg.auth.env ?: "${key.uppercase()}_API_KEY",
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
                quirks = providerCfg.responsesQuirks(GrokProvider.defaultQuirks()),
            )
        } else {
            OpenAiResponsesProvider(
                tuning = tuning,
                showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning,
                configEffort = cfg.effort,
                configSummary = cfg.summary,
                quirks = providerCfg.responsesQuirks(OpenAiResponsesProvider.defaultQuirks()),
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
    // /login interception only makes sense for browser-OAuth providers; api-key heads have no
    // sign-in flow, so they get no custom /login (just the disabled Anthropic built-in). Returns
    // (loginCommand, signInLabel) — both blank when there is no OAuth flow.
    private fun loginInterception(providerCfg: ProviderConfig, head: HeadConfig, key: String): Pair<String, String> {
        val label = when (providerCfg.auth.kind) {
            "chatgpt-oauth" -> "Codex (ChatGPT)"
            "grok-oauth" -> "Grok (xAI)"
            "kimi-oauth" -> "Kimi (Moonshot)"
            else -> ""
        }
        val command = if (label.isEmpty()) "" else "${head.claude.command ?: key} login"
        return command to label
    }

    private fun assembleHead(ctx: ProviderBuild, controlPort: Int): ManagedHead {
        val key = ctx.key
        val head = ctx.head
        val cfg = ctx.cfg
        val wired = buildProvider(ctx)
        val usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key))
        val compactStats = CompactStats(statePaths.compactStatsFile(key))
        val perfStats = PerfStats(statePaths.perfStatsFile(key))
        val logFile = statePaths.logsDir.resolve("$key-${head.port}.log")
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
                gate = InflightGate(
                    maxInflight = { config.getConfig().maxInflight },
                    maxQueued = { config.getConfig().maxQueued },
                ),
                shadow = ShadowClassifier(log = log),
                compactStats = compactStats,
                usageStore = usageStore,
                perfStats = perfStats,
                log = log,
            ),
        )
        return ManagedHead(
            head = server,
            auth = wired.auth,
            usage = UsageStoreSource(usageStore),
            compact = CompactStatsSource(compactStats),
            logs = LogFileSource(logFile),
            warnPct = cfg.usageWarnPct,
            warnTokens5h = cfg.usageWarnTokens5h,
            authKind = ctx.providerCfg.auth.kind,
            launchSpec = launchSpecFor(ctx, controlPort),
            perf = PerfStatsSource(perfStats),
        )
    }

    private fun launchSpecFor(ctx: ProviderBuild, controlPort: Int): LaunchSpec {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val configDir = Paths.get(TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"))
        val (loginCommand, signInLabel) = loginInterception(providerCfg, head, key)
        return LaunchSpec(
            configDir = configDir,
            pinnedModel = head.pinnedModel,
            availableModelIds = ctx.catalog.availableModelIds(),
            modelLabels = providerCfg.models.associate { it.id to it.label.ifEmpty { it.id } },
            contextWindow = ctx.catalog.contextWindowFor(head.pinnedModel).toInt(),
            modelOptionsCache = modelOptionsCache(providerCfg),
            statuslineCommand = "curl -sS --data-binary @- http://127.0.0.1:$controlPort/statusline/$key",
            // The installed wrapper (`<command> login`) runs this head's provider sign-in; the
            // materialized /login command + UserPromptSubmit hook route the user here.
            loginCommand = loginCommand,
            signInLabel = signInLabel,
            policy = ClaudePolicy(share = topology.claude.share.toSet(), isolate = head.claude.isolate.toSet()),
            port = head.port,
        )
    }

    public companion object {
        public fun dashboardFrom(distPath: Path): () -> String = {
            runCatching { Files.readString(distPath) }
                .getOrDefault("<!doctype html><title>splice</title><p>dashboard build missing</p>")
        }
    }
}
