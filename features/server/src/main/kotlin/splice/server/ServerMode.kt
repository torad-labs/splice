// NEW: server mode (docs/specs/server-mode.md, F1 and F2). One daemon's server mode, as the daemon holds it: the keys
// and the people it keeps under `<state>/server`, the operations each door asks of them, the identity provider a team
// member signs in with, and the listener that answers key holders. The daemon builds it when splice.toml has a
// `[server]`, opens it once its heads are up, and closes it first at stop.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.config.TurnKey
import splice.core.server.ServedTurnBoard
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.server.metrics.MetricPeople
import splice.server.metrics.ServerMetrics
import splice.share.RefusalVoice
import splice.share.RelayClock
import splice.share.RelaySpeaker
import splice.share.ShareRelay
import splice.share.SharedHead
import splice.share.SharedHeads
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Path
import java.time.Clock

/** Of the heads this machine can offer, the ones `[server] commands` names, by command or by head key. */
internal class ServedHeads(private val all: SharedHeads, private val commands: List<String>) : SharedHeads {
    override val machine: String get() = all.machine

    override fun offered(): List<SharedHead> = all.offered().filter { it.command in commands || it.key in commands }
}

/** How the listener reaches this machine's heads: the turn key they admit, the control port beside them, and the board
 *  they say on how each turn the listener handed them went (F5); and the environment a bucket export's key is read
 *  from (F5). */
public data class ServerReach(
    val turnKey: TurnKey,
    val controlPort: Int,
    val served: ServedTurnBoard = ServedTurnBoard(),
    val env: EnvReader = EnvReader { null },
)

public class ServerMode(
    private val server: ServerConfig,
    /** `<state>/server`, where the keys and the people are kept. A file there that cannot be read throws here. */
    folder: Path,
    /** Blocking work runs here: the two files' writes, a head's launch, the relay's I/O. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    /** What time it is, for a key that expires and for the minute a person is counted over. */
    private val clock: Clock = Clock.systemUTC(),
    /** The servers behind this machine's providers (F4); none for a daemon whose providers are each one server. */
    upstreams: HeadUpstreams = object : HeadUpstreams {},
    /** What a kept-alive turn's silences are waited out on (F4, `keepalive_seconds`): the process clock. */
    private val waiter: Waiter = ProcessWaiter(),
) {
    private val keys = ServerKeys(folder.resolve(KEYS_FILE), io, clock)
    private val people = ServerPeople(folder.resolve(PEOPLE_FILE), io)
    private val limits = PersonLimits(server.limits, people, clock)

    /** The keys' operations, as the CLI and the control plane ask them. */
    public val keyApi: KeyApi = KeyApi(keys, log)

    /** The upstreams as they read now, as the control plane lists them. */
    public val upstreamApi: UpstreamApi = UpstreamApi(upstreams)

    /** Each person's settings, as the CLI and the control plane set them. */
    public val personApi: PersonApi = PersonApi(people, log, upstreamApi)

    /** The server's books (F5): its usage ledger and reports, its budgets, and the ledger's export, as the CLI, the
     *  control plane and an admin's key ask them. */
    public val books: ServerBookkeeping =
        ServerBookkeeping(folder, server, people, upstreamApi, BookRun(io, clock, waiter, log))

    /** Each person's traffic, live (F6): what an admin scrapes from the listener and the operator's OTLP export sends. */
    public val metrics: ServerMetrics = ServerMetrics(MetricPeople(keys, people, server.teams, limits), clock)

    @Volatile
    private var listener: ServerListener? = null

    /** Opens the listener on [address], serving the heads of [heads] that `[server] commands` names. [configDir] is
     *  splice.toml's directory, which a relative PEM path is under. Throws when the address cannot be bound or the
     *  PEM files cannot be read. [signinSecret] is the client secret `[server.signin] client_secret_env` names, as
     *  the daemon's environment holds it (F2). */
    public fun open(
        address: ServerAddress,
        heads: SharedHeads,
        reach: ServerReach,
        configDir: Path,
        signinSecret: String? = null,
    ) {
        val speaker = RelaySpeaker(RefusalVoice(SERVER_ERROR_PREFIX), SERVER_LOG_TAG)
        val served = ServedHeads(heads, server.commands)
        metrics.serving(served)
        val parts = ServerParts(
            keys = keys,
            limits = limits,
            heads = served,
            relay = ShareRelay(reach.turnKey, reach.controlPort, heads.machine, io, log, speaker, RelayClock(waiter)),
            people = people,
            upstreams = upstreamApi,
            books = books.open(reach, metrics),
        )
        val tls = server.pem(configDir)?.let { PemTls(it).keyStore() }
        val signin = server.signin?.let { signin ->
            val issuer = SigninIssuer(SigninClient(signin, signinSecret), io, clock)
            SigninParts(signin, issuer, IdTokens(signin, issuer, clock))
        }
        val opened = ServerListener(server, parts, tls, AdminReads(books.usageApi, metrics), io, log, signin)
        opened.start(address)
        listener = opened
        books.exporting(reach.env)
    }

    /** Closes the listener and writes when each key was last used, so a restart loses none of it. */
    public suspend fun close() {
        listener?.stop()
        listener = null
        books.close()
        keys.keepUse(olderThanMs = 0)
    }
}

private const val KEYS_FILE = "keys.json"
private const val PEOPLE_FILE = "people.json"
private const val SERVER_LOG_TAG = "server"
