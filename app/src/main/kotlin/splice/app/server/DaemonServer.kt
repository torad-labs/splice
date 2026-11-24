// NEW: server mode (docs/specs/server-mode.md, F1). The daemon's part of it: when splice.toml has a `[server]`, the
// keys and the people kept under `<state>/server` and the listener that answers key holders, opened once the heads
// are up and closed first at stop. A server mode that can't start (a port another program holds, a PEM file that is
// no certificate, a keys file that cannot be read) is said in the log and to every `splice server` verb, and leaves
// the rest of the daemon serving. It is also how server mode reads the servers behind a head (F4): the pool of the
// provider that head's splice.toml table names, the caller's headers that provider's `forward_headers` names, and
// that provider itself when it speaks openai-chat, which is where an OpenAI-style client's requests are relayed
// (DaemonChatServer). While it runs, its per-person metrics go to the operator's OTLP collector when
// `[telemetry.operator]` sends metrics (F6).
package splice.app.server

import splice.app.control.ManagedHead
import splice.app.control.ServerModeSource
import splice.app.share.ManagedSharedHeads
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.pool.UpstreamPool
import splice.core.server.ServedTokens
import splice.core.server.ServedTurnBoard
import splice.core.server.ServerModRoutes
import splice.core.topology.Dialect
import splice.core.topology.HourlyPrices
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.observability.served.ServedMetricsExport
import splice.server.ChatServer
import splice.server.HeadUpstreams
import splice.server.ServerMode
import splice.server.ServerReach
import splice.server.TeamGateway
import splice.server.backup.BackupRun
import splice.server.backup.ServerBackupStore
import splice.share.ShareMachine
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Path
import java.time.Clock

internal class DaemonServer(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val log: LogSink,
    /** splice's state folder; the server keeps its keys and its people under `server/` in it. */
    private val stateDir: Path,
    /** splice.toml's directory, which a relative `tls_cert` or `tls_key` is under. */
    private val configDir: Path,
    /** The pools of the providers that list upstreams (F4), as the heads' providers hold them. */
    private val pools: UpstreamPools,
) : ServerModeSource, HeadUpstreams {
    init {
        // The pools hold server mode's per-upstream cap from their first turn, whoever sends it (#791).
        pools.capEach(topology.server.limits.inflightPerUpstream)
    }

    @Volatile
    private var running: ServerMode? = null

    @Volatile
    private var down: String = NO_SERVER

    // The server's per-person metrics on their way to the operator's OTLP collector (F6), while server mode runs.
    @Volatile
    private var metricsExport: ServedMetricsExport? = null

    // The heads the server serves, once it opened: what an OpenAI-style turn is priced at is its head's rate card.
    @Volatile
    private var heads: Map<String, ManagedHead> = emptyMap()

    override fun mode(): ServerMode? = running

    override fun absent(): String = down

    override fun all(): List<UpstreamPool> = pools.all()

    /** The pool of the provider [head] runs on; a provider with no head (the team door's) is asked by its own key. */
    override fun of(head: String): UpstreamPool? =
        (topology.heads[head]?.provider ?: head.takeIf { it in topology.providers })?.let { pools.named(it) }

    override fun chat(head: String): ChatServer? {
        val key = topology.heads[head]?.provider ?: return null
        val provider = topology.providers[key]?.takeIf { it.dialect == Dialect.OPENAI_CHAT } ?: return null
        return DaemonChatServer(key, provider, pools)
    }

    override fun forwarded(head: String): List<String> =
        topology.heads[head]?.let { topology.providers[it.provider] }?.forwarded?.carried.orEmpty()

    override fun price(head: String, model: String, tokens: ServedTokens): Double? {
        val catalog = heads[head]?.statusline?.catalog ?: return null
        val billed = mapOf(
            PerfKeys.IN_TOKENS to tokens.prompt,
            PerfKeys.CACHED_TOKENS to tokens.cached,
            PerfKeys.OUT_TOKENS to tokens.completion,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
        )
        return TurnPrice(catalog).usd(model, billed)
    }

    override fun hourly(): Map<String, Double> = HourlyPrices(topology.providers).prices()

    /** Opens server mode when `[server]` names an address, serving [heads], which say how each turn went on [served].
     *  [gateway] serves a team's turns at `/door` on the providers, with no head, and [mods] the modes that answer key
     *  holders under `/server/mod/<name>/...`. */
    fun open(
        heads: Map<String, ManagedHead>,
        served: ServedTurnBoard,
        gateway: TeamGateway? = null,
        mods: ServerModRoutes? = null,
    ) {
        val address = topology.server.address ?: return
        this.heads = heads
        Cancellables.runCatchingBestEffort {
            val offered = ManagedSharedHeads(heads, topology, config, ShareMachine(log).name())
            val folder = stateDir.resolve(SERVER_STATE)
            val env = EnvReader(System::getenv)
            val io = ProcessDispatchers().io()
            val reach = ServerReach(turnKey, served, env, gateway, mods)
            ServerBackupStore(
                topology.server.backup,
                folder,
                BackupRun(io, Clock.systemUTC(), ProcessWaiter(), log),
            ).restoreIfEmpty(env)
            ServerMode(topology.server, folder, io, log, upstreams = this)
                .also { it.open(address, offered, reach, configDir, secret()) }
        }.onSuccess { mode ->
            running = mode
            metricsExport = ServedMetricsExport(config.getConfig().perfExportConfig(), mode.metrics, log)
        }.onFailure { failure ->
            val why = SafeFailureText.render(failure)
            down = "server mode did not start on ${topology.server.listen}: $why"
            log("[server] not serving on ${topology.server.listen}: $why\n")
        }
    }

    /** The sign-in's client secret from the variable `client_secret_env` names; a variable named and unset is said,
     *  since the identity provider will then refuse every code this server trades. */
    private fun secret(): String? {
        val name = topology.server.signin?.clientSecretEnv?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val value = EnvReader(System::getenv)(name)?.takeIf(String::isNotEmpty)
        if (value == null) log("[server] sign-in: $name is not set, so the identity provider will refuse every code\n")
        return value
    }

    suspend fun close() {
        running?.close()
        running = null
        metricsExport?.close()
        metricsExport = null
    }
}

// The server's folder under splice's state.
private const val SERVER_STATE = "server"

private const val NO_SERVER = "this daemon runs no server: splice.toml has no [server]"
