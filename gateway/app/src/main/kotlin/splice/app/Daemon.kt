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
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.CodexProvider
import splice.provider.codex.RefreshedTokens
import splice.spi.InflightGate
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
        @Suppress("LoopWithTooManyJumpStatements") // skip-unwired-dialect guard
        for ((key, head) in topology.heads) {
            val providerCfg = topology.providers[head.provider] ?: continue
            if (providerCfg.dialect != Dialect.OPENAI_RESPONSES) {
                log("[daemon] head $key: dialect ${providerCfg.dialect} not yet wired; skipping\n")
                continue
            }
            val catalog = providerCfg.catalogFor(head)
            heads[key] = buildCodexHead(key, head, providerCfg, catalog, watchdog, cfg)
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

    @Suppress("LongParameterList", "LongMethod")
    private fun buildCodexHead(
        key: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        catalog: ModelCatalog,
        watchdog: WatchdogBudget,
        cfg: SpliceConfig,
    ): ManagedHead {
        val authPath = Paths.get(TopologyLoader.expandHome(providerCfg.auth.file ?: cfg.codexAuthPath))
        val tokenUrl = "${providerCfg.baseUrl.removeSuffix("/codex")}/oauth/token"
        val auth = CodexAuthProvider(
            authPath = authPath,
            authCacheMs = cfg.authCacheMs,
            refreshCall = { rt -> refreshCall(tokenUrl, rt) },
        )
        val provider = CodexProvider(
            key = key,
            label = head.claude.command ?: key,
            catalog = catalog,
            pinnedModel = head.pinnedModel,
            auth = auth,
            baseUrl = providerCfg.baseUrl,
            watchdog = watchdog,
            showReasoning = cfg.showReasoning,
            replayReasoning = cfg.replayReasoning,
            configEffort = cfg.effort,
            configSummary = cfg.summary,
            accountIdHeader = providerCfg.quirks.accountIdHeader,
        )
        val usageStore = UsageStore(statePaths.usageFile(key), statePaths.ratelimitFile(key))
        val compactStats = CompactStats(statePaths.compactStatsFile(key))
        val logFile = statePaths.logsDir.resolve("$key-${head.port}.log")
        val server = HeadServer(
            provider = provider,
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
        val configDir = java.nio.file.Paths.get(
            TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"),
        )
        val launchSpec = LaunchSpec(
            configDir = configDir,
            pinnedModel = head.pinnedModel,
            availableModelIds = catalog.availableModelIds(),
            modelOptionsCache = buildJsonObject { },
            policy = ClaudePolicy(
                share = topology.claude.share.toSet(),
                isolate = head.claude.isolate.toSet(),
            ),
            port = head.port,
        )
        return ManagedHead(
            head = server,
            auth = auth,
            usage = UsageStoreSource(usageStore),
            compact = CompactStatsSource(compactStats),
            logs = LogFileSource(logFile),
            warnPct = cfg.usageWarnPct,
            warnTokens5h = cfg.usageWarnTokens5h,
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
