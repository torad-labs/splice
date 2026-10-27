// A codex head assembled as the daemon assembles it, its model list asked of a loopback ChatGPT backend with the
// credential its sign-in left on disk, and the daemon's two roster beats (hourly, and the credential's) held by the
// test.
package splice.app.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.control.ManagedHead
import splice.app.daemon.HeadCatalogs
import splice.app.daemon.TopologyWindows
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.StartQuotaPoller
import splice.app.provider.EndpointModels
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderAssembly
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

internal const val CODEX_HEAD = "codex"
internal const val CODE_MODE_MODEL = "gpt-6-synthetic"

/** One of the daemon's roster loops, held by the test: each beat runs one round, and returns once the loop is waiting
 *  for the next. */
internal class HeldBeat : Ticker {
    private val waiting = Channel<Long>(Channel.UNLIMITED)
    private val beats = Channel<Boolean>(Channel.UNLIMITED)

    override suspend fun awaitTick(intervalMs: Long): Boolean {
        waiting.send(intervalMs)
        return beats.receive()
    }

    /** The interval the loop asked to wait, once it waits. */
    suspend fun waiting(): Long = waiting.receive()

    suspend fun beat() {
        beats.send(true)
        waiting.receive()
    }
}

internal class SignInRosterFixture(tmp: Path, parent: CoroutineScope) {
    private val scope = LifecycleScope(parent.coroutineContext)
    private val paths = StatePaths(baseOverride = tmp.resolve("state"))
    val backend = ChatGptListBackend()
    val login = SyntheticChatGptLogin(tmp.resolve("codex-auth.json"))
    private val config = ConfigService(paths, envReader = { null })
    private val mgmt = MgmtKey(paths)
    private val signIn = SignInPlanner()

    /** Every line the daemon would write to its log, in order. */
    val daemonLog = CopyOnWriteArrayList<String>()
    private val log = LogSink { daemonLog += it }
    val hourly = HeldBeat()
    val credentialBeat = HeldBeat()

    val provider = ProviderConfig(
        dialect = Dialect.OPENAI_RESPONSES,
        baseUrl = backend.baseUrl,
        auth = AuthConfig(AuthKind.ChatgptOAuth.wire, file = tmp.resolve("codex-auth.json").toString()),
        models = listOf(ModelEntry(CODE_MODE_MODEL, contextWindow = 272_000)),
        local = false,
    )
    private val heads = mapOf(CODEX_HEAD to provider)
    private val rosters = ModelRosters(
        paths,
        log,
        EndpointModels(EnvReader { null }),
        ticker = hourly,
        credentialTicker = credentialBeat,
    )
    private val head = HeadConfig("codex", 0, "claude-codex--", CODE_MODE_MODEL)
    private val topology = Topology(providers = heads, heads = mapOf(CODEX_HEAD to head))
    private val inputs = HeadBuildInputs(config, signIn, rosters)
    private val windows = TopologyWindows(
        null,
        topology,
        "",
        HeadCatalogs { key, h, p, _ -> inputs.catalogFor(key, h, p, false) },
        {},
    )
    private var managed: ManagedHead? = null
    private val client = HttpClient.newHttpClient()

    /** What the daemon does at its start: ask every head's list, build each head's inputs as Daemon.start builds them
     *  (HeadBuildInputs.providerContext, so the head reads the roster through the daemon's own wrapper), start the
     *  heads, then start both roster loops. The legacy knobs are off: they would point the head at the real backend. */
    suspend fun start() {
        rosters.resolve(heads)
        val ctx = inputs.providerContext(CODEX_HEAD, head, provider, legacyKnobsGovern = false)
        val assembled = factory().assembleHead(windows.attach(ctx, false), 0)
        managed = assembled
        assembled.head.start()
        rosters.start(scope, heads)
        hourly.waiting()
        credentialBeat.waiting()
    }

    private fun factory() = ManagedHeadFactory(
        statePaths = paths,
        providerAssembly = ProviderAssembly(
            paths,
            scope,
            log,
            TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic-denied") },
        ),
        serving = HeadServing(HeadServerFactory(config, mgmt, log)),
        launchSpecFactory = LaunchSpecFactory(topology, signIn, mgmt, inputs),
        log = log,
        quotaSeams = QuotaPollSeams(scope, log, startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null }),
    )

    /** A Claude Code turn on [model] that offers one tool of its own, as every Claude Code turn does. */
    fun turn(model: String = CODE_MODE_MODEL): HttpResponse<String> = request(
        "/v1/messages",
        """{"model":"$model","max_tokens":64,"stream":true,""" +
            """"tools":[{"name":"synthetic_tool","description":"A synthetic tool.",""" +
            """"input_schema":{"type":"object","properties":{}}}],""" +
            """"messages":[{"role":"user","content":"synthetic request"}]}""",
    )

    /** The head's model picker, as Claude Code reads it. */
    fun picker(): HttpResponse<String> = request("/v1/models")

    private fun request(path: String, body: String? = null): HttpResponse<String> {
        val port = checkNotNull(managed) { "start the fixture first" }.head.port
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
        managed?.head?.stop()
        windows.close()
        backend.close()
    }
}
