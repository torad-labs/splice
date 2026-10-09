// A real assembled head, its stores and the control routes, kept across controlled hourly roster refreshes.
package splice.app.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.control.ControlAuth
import splice.app.control.ControlRuntime
import splice.app.control.ControlServer
import splice.app.control.ManagedHead
import splice.app.control.controlServerFor
import splice.app.daemon.HeadCatalogs
import splice.app.daemon.TopologyWindows
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.StartQuotaPoller
import splice.app.provider.HeadBuildInputs
import splice.app.provider.HeadModelsSource
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.models.discovery.Discovery
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal class RunningRosterFixture(tmp: Path, parent: CoroutineScope) {
    private val scope = LifecycleScope(parent.coroutineContext)
    val paths = StatePaths(baseOverride = tmp.resolve("state"))
    private val upstream = RosterUpstream()
    private val config = ConfigService(paths, envReader = { null })
    private val mgmt = MgmtKey(paths)
    private val signIn = SignInPlanner()
    private val keyFile = tmp.resolve("synthetic-key")
    private val tick = Channel<Boolean>(Channel.UNLIMITED)
    private val awaiting = Channel<Long>(Channel.UNLIMITED)

    @Volatile var models = listOf(DiscoveredModel("synthetic-original"))

    val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = upstream.baseUrl,
        auth = AuthConfig("api-key", file = keyFile.toString()),
        models = listOf(ModelEntry("synthetic-original", contextWindow = 128_000)),
        local = false,
    )
    val rosters = ModelRosters(
        paths,
        {},
        HeadModelsSource { _, _ -> Discovery.Found("${upstream.baseUrl}/models", models) },
        ticker = Ticker { interval ->
            awaiting.send(interval)
            tick.receive()
        },
    )
    private val head = HeadConfig("synthetic", 0, "claude-synthetic--", "synthetic-original")
    private val topology = Topology(providers = mapOf("synthetic" to provider), heads = mapOf("synthetic" to head))
    private val inputs = HeadBuildInputs(config, signIn, rosters)
    private val windows = TopologyWindows(
        null,
        topology,
        "",
        HeadCatalogs { key, h, p, _ -> inputs.catalogFor(key, h, p, false) },
        {},
    )
    lateinit var managed: ManagedHead
        private set
    private lateinit var control: ControlServer
    private val client = HttpClient.newHttpClient()
    private val teams = TeamStore(tmp.resolve("teams.json"), clock = WallClock { 0L })
    lateinit var teamId: String
        private set
    val requests: List<String> get() = upstream.requests

    suspend fun start() {
        SecureFile.writeAtomic0600(keyFile, "synthetic-fixture-key")
        rosters.resolve(mapOf("synthetic" to provider))
        val ctx = ProviderBuild(
            key = "synthetic",
            head = head,
            providerCfg = provider,
            catalog = provider.catalogFor(head, discovered = rosters.forHead("synthetic")),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            cfg = config.getConfig("synthetic"),
            loginCommand = "",
            discovered = rosters,
        )
        val attached = windows.attach(ctx, false)
        managed = factory().assembleHead(attached, 0)
        managed.head.start()
        control = controlServer()
        teamId = teams.create(
            Team(
                name = "Synthetic pricing",
                repo = paths.stateDir.toString(),
                slots = listOf(TeamSlot("synthetic-lead", "lead", "synthetic", session = "synthetic-session", lead = true)),
            ),
            "synthetic-team-key",
        ).first.id
        control.ports.teams = teams
        control.ports.declaredHeads = DeclaredHeads { mapOf("synthetic" to DeclaredHead("synthetic", null)) }
        control.start()
        rosters.start(scope, mapOf("synthetic" to provider))
        check(awaiting.receive() == 3_600_000L)
    }

    private fun controlServer() = controlServerFor(
        0,
        mapOf("synthetic" to managed),
        config,
        ControlAuth(mgmt, {}),
        runtime = ControlRuntime(
            sessions = object : SessionSource {
                override fun read(): List<SessionRecord> = emptyList()
                override fun list(): SessionListing = SessionListing(emptyList())
            },
        ),
    )

    private fun factory() = ManagedHeadFactory(
        statePaths = paths,
        providerAssembly = ProviderAssembly(
            paths,
            scope,
            {},
            TokenUrlRefreshCall { _, _ -> error("no OAuth in fixture") },
        ),
        serving = HeadServing(HeadServerFactory(config, mgmt, {})),
        launchSpecFactory = LaunchSpecFactory(topology, signIn, mgmt, inputs),
        log = {},
        quotaSeams = QuotaPollSeams(
            scope,
            {},
            startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null },
        ),
    )

    suspend fun refresh() {
        tick.send(true)
        check(awaiting.receive() == 3_600_000L) // next tick is requested after publication
    }

    fun headGet(path: String): HttpResponse<String> = request(managed.head.port, path)
    fun controlGet(path: String): HttpResponse<String> = request(control.listeningPort, path)
    fun turn(model: String): HttpResponse<String> = request(
        managed.head.port,
        "/v1/messages",
        """{"model":"$model","max_tokens":8,"stream":true,"messages":[{"role":"user","content":"synthetic request"}]}""",
    )
    fun statusline(model: String): HttpResponse<String> = request(
        control.listeningPort,
        "/statusline/synthetic",
        """{"session_id":"synthetic-session","model":{"id":"$model"},"cost":{"total_cost_usd":999}}""",
    )

    private fun request(port: Int, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Authorization", "Bearer ${mgmt.get()}")
            .header("x-claude-code-session-id", "synthetic-session")
        if (body != null) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        if (::control.isInitialized) control.stop()
        if (::managed.isInitialized) managed.head.stop()
        windows.close()
        upstream.close()
    }
}
