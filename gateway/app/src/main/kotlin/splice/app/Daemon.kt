// NEW: the daemon assembly (P4-SUP) — one JVM hosting the control plane + every enabled head.
// Builds each head from topology (provider wired to its dialect + auth + stores), starts control
// :3096 and each head port. suspend all the way (the runBlocking bridge lives in Main); version
// handshake = /health version string equality (a daemon bump restarts all heads together — the
// documented change).
package splice.app

import kotlinx.serialization.json.buildJsonObject
import splice.control.ControlServer
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.control.ManagedHead
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
import splice.core.turn.WatchdogBudget
import splice.dialect.chat.ChatQuirks
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.CodexProvider
import splice.provider.codex.RefreshedTokens
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokProvider
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.provider.openai.OpenAiResponsesProvider
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.UpstreamClient
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

public class Daemon(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit = { System.err.print(it) },
    private val refreshCall: suspend (tokenUrl: String, refreshToken: String) -> RefreshedTokens? = ::codexRefresh,
) {
    private val config = ConfigService(statePaths)
    private val mgmtKey = MgmtKey(statePaths)

    @Suppress("LateinitUsage") // set once in start(); the daemon is not usable before it
    private var control: ControlServer? = null
    private val heads = LinkedHashMap<String, ManagedHead>()

    public suspend fun start() {
        val cfg = config.getConfig()
        val watchdog = WatchdogBudget(
            firstByteTimeout = cfg.firstByteTimeoutMs.milliseconds,
            streamIdle = cfg.streamIdleMs.milliseconds,
            totalCap = cfg.upstreamTimeoutMs.milliseconds,
        )
        for ((key, head) in topology.heads) {
            val providerCfg = topology.providers[head.provider] ?: continue
            val catalog = providerCfg.catalogFor(head)
            heads[key] = assembleHead(key, head, providerCfg, catalog, watchdog, cfg)
        }
        // topology owns the control port (loaded once at start, restart-required); the
        // ConfigService knob is only the hot-knob default when topology omits it.
        val controlPort = topology.daemon.controlPort.takeIf { it > 0 } ?: cfg.controlPort
        val statuslineCmd = "curl -sS --data-binary @- http://127.0.0.1:$controlPort/statusline/"
        val materializerHome = statePaths.rootDir.parent ?: statePaths.rootDir
        val launchService = LaunchService(ClaudeConfigMaterializer(materializerHome, statuslineCmd))
        val srv = ControlServer(controlPort, heads, config, mgmtKey, dashboardHtml, log, launchService)
        control = srv
        srv.start()
        heads.values.forEach { it.head.start() }
        log("[daemon] up: control :$controlPort, heads ${heads.keys}\n")
    }

    public suspend fun stop() {
        heads.values.forEach { runCatching { it.head.stop() } }
        control?.stop()
    }

    /** Provider + its auth, chosen by (dialect, auth.kind) — the multi-provider dispatch. */
    private data class Wired(val provider: Provider, val auth: RefreshableAuthProvider)

    // The dispatch that makes the daemon genuinely multi-provider: codex (responses+oauth), grok
    // (responses+api-key, session cache key), openai-platform (responses+api-key, hash cache key),
    // and ANY openai-compatible vendor (chat dialect + api-key) — the last is pure TOML, zero code.
    @Suppress("LongParameterList")
    private fun buildProvider(
        key: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): Wired {
        val label = head.claude.command ?: key
        return when (providerCfg.dialect) {
            Dialect.OPENAI_RESPONSES -> responsesProvider(key, label, head, providerCfg, catalog, watchdog, cfg)
            Dialect.OPENAI_CHAT -> {
                val auth = ApiKeyAuthProvider(
                    envVar = providerCfg.auth.env ?: "${key.uppercase()}_API_KEY",
                    keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
                )
                Wired(
                    OpenAiChatProvider(
                        key = key,
                        label = label,
                        catalog = catalog,
                        pinnedModel = head.pinnedModel,
                        auth = auth,
                        baseUrl = providerCfg.baseUrl,
                        watchdog = watchdog,
                        quirks = ChatQuirks(providerTag = key),
                    ),
                    auth,
                )
            }
            // anthropic-passthrough is the P0-XAI fidelity probe path — not built (credential-gated).
            Dialect.ANTHROPIC_PASSTHROUGH -> error("head $key: anthropic-passthrough dialect not wired")
        }
    }

    @Suppress("LongParameterList")
    private fun responsesProvider(
        key: String,
        label: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): Wired = when (providerCfg.auth.kind) {
        "chatgpt-oauth" -> {
            val tokenUrl = "${providerCfg.baseUrl.removeSuffix("/codex")}/oauth/token"
            val auth = CodexAuthProvider(
                authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.codexAuthPath)),
                authCacheMs = cfg.authCacheMs,
                refreshCall = { rt -> refreshCall(tokenUrl, rt) },
            )
            Wired(
                CodexProvider(
                    key = key, label = label, catalog = catalog, pinnedModel = head.pinnedModel, auth = auth,
                    baseUrl = providerCfg.baseUrl, watchdog = watchdog, showReasoning = cfg.showReasoning,
                    replayReasoning = cfg.replayReasoning, configEffort = cfg.effort, configSummary = cfg.summary,
                    accountIdHeader = providerCfg.quirks.accountIdHeader,
                ),
                auth,
            )
        }
        "grok-oauth" -> grokOAuthProvider(key, label, head, providerCfg, catalog, watchdog, cfg)
        else -> apiKeyResponsesProvider(key, label, head, providerCfg, catalog, watchdog, cfg)
    }

    // grok via the SuperGrok/X-Premium+ browser OAuth (~/.grok/auth.json, Bearer + refresh) — the
    // same Responses dialect + grok quirks, only the auth differs from the api-key path.
    @Suppress("LongParameterList")
    private fun grokOAuthProvider(
        key: String,
        label: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): Wired {
        val tokenUrl = GrokOAuthEndpoints.tokenUrl(System::getenv)
        val auth = GrokAuthProvider(
            authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: "~/.grok/auth.json")),
            authCacheMs = cfg.authCacheMs,
            refreshCall = { rt -> grokRefresh(tokenUrl, rt) },
        )
        return Wired(
            GrokProvider(
                key = key, label = label, catalog = catalog, pinnedModel = head.pinnedModel, auth = auth,
                baseUrl = providerCfg.baseUrl, watchdog = watchdog, showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning, configEffort = cfg.effort,
            ),
            auth,
        )
    }

    // api-key + responses: grok (session-id cache key, effort-clamped, no summary) vs openai
    // platform (first-message-hash, summary). The TOML cache_key quirk selects the profile.
    @Suppress("LongParameterList")
    private fun apiKeyResponsesProvider(
        key: String,
        label: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): Wired {
        val auth = ApiKeyAuthProvider(
            envVar = providerCfg.auth.env ?: "${key.uppercase()}_API_KEY",
            keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
        )
        val provider = if (providerCfg.quirks.cacheKey == "session-id") {
            GrokProvider(
                key = key, label = label, catalog = catalog, pinnedModel = head.pinnedModel, auth = auth,
                baseUrl = providerCfg.baseUrl, watchdog = watchdog, showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning, configEffort = cfg.effort,
            )
        } else {
            OpenAiResponsesProvider(
                key = key, label = label, catalog = catalog, pinnedModel = head.pinnedModel, auth = auth,
                baseUrl = providerCfg.baseUrl, watchdog = watchdog, showReasoning = cfg.showReasoning,
                replayReasoning = cfg.replayReasoning, configEffort = cfg.effort, configSummary = cfg.summary,
            )
        }
        return Wired(provider, auth)
    }

    // Common assembly shared by every provider: stores, the generic HeadServer, launch spec.
    @Suppress("LongParameterList")
    private fun assembleHead(
        key: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): ManagedHead {
        val wired = buildProvider(key, head, providerCfg, catalog, watchdog, cfg)
        val usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key))
        val compactStats = CompactStats(statePaths.compactStatsFile(key))
        val logFile = statePaths.logsDir.resolve("$key-${head.port}.log")
        val server = HeadServer(
            provider = wired.provider,
            listenPort = head.port,
            deps = HeadDeps(
                upstream = UpstreamClient(cfg.firstByteTimeoutMs, cfg.upstreamTimeoutMs, cfg.upstreamRetries),
                gate = InflightGate({ config.getConfig().maxInflight }),
                shadow = ShadowClassifier(log = log),
                compactStats = compactStats,
                usageStore = usageStore,
                log = log,
            ),
        )
        val configDir = Paths.get(TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"))
        val launchSpec = LaunchSpec(
            configDir = configDir,
            pinnedModel = head.pinnedModel,
            availableModelIds = catalog.availableModelIds(),
            modelLabels = providerCfg.models.associate { it.id to (it.label.ifEmpty { it.id }) },
            contextWindow = catalog.contextWindowFor(head.pinnedModel).toInt(),
            modelOptionsCache = buildJsonObject { },
            policy = ClaudePolicy(share = topology.claude.share.toSet(), isolate = head.claude.isolate.toSet()),
            port = head.port,
        )
        return ManagedHead(
            head = server,
            auth = wired.auth,
            usage = UsageStoreSource(usageStore),
            compact = CompactStatsSource(compactStats),
            logs = LogFileSource(logFile),
            warnPct = cfg.usageWarnPct,
            warnTokens5h = cfg.usageWarnTokens5h,
            authKind = providerCfg.auth.kind,
            launchSpec = launchSpec,
        )
    }

    public companion object {
        public fun dashboardFrom(distPath: Path): () -> String = {
            runCatching { java.nio.file.Files.readString(distPath) }
                .getOrDefault("<!doctype html><title>splice</title><p>dashboard build missing</p>")
        }
    }
}
