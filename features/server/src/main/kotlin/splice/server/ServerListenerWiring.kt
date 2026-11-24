// Builds the team server's HTTP/TLS listener and OIDC sign-in parts from ServerMode's stores.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.server.metrics.ServerMetrics
import splice.server.signin.IdTokens
import splice.server.signin.SigninClient
import splice.server.signin.SigninIssuer
import splice.server.signin.SigninParts
import splice.share.RefusalVoice
import splice.share.RelayClock
import splice.share.RelaySpeaker
import splice.share.ShareRelay
import splice.share.SharedHeads
import splice.upstream.Waiter
import java.nio.file.Path
import java.time.Clock

internal data class ListenerStores(
    val keys: ServerKeys,
    val people: ServerPeople,
    val limits: PersonLimits,
    val upstreamApi: UpstreamApi,
    val books: ServerBookkeeping,
    val metrics: ServerMetrics,
    val admin: AdminReads,
)

internal class ServerListenerWiring(
    private val server: ServerConfig,
    private val stores: ListenerStores,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val clock: Clock,
    private val waiter: Waiter,
) {
    fun build(
        heads: SharedHeads,
        reach: ServerReach,
        configDir: Path,
        signinSecret: String?,
    ): ServerListener {
        val speaker = RelaySpeaker(RefusalVoice(SERVER_ERROR_PREFIX), SERVER_LOG_TAG)
        val served = ServedHeads(heads, server.commands)
        stores.metrics.serving(served)
        val parts = ServerParts(
            keys = stores.keys,
            limits = stores.limits,
            heads = served,
            carriers = TurnCarriers(
                ShareRelay(reach.turnKey, heads.machine, log, speaker, RelayClock(waiter)),
                reach.gateway,
            ),
            people = stores.people,
            upstreams = stores.upstreamApi,
            books = stores.books.open(reach, stores.metrics),
        )
        val tls = server.pem(configDir)?.let { PemTls(it).keyStore() }
        val signin = server.signin?.let { signin ->
            val issuer = SigninIssuer(SigninClient(signin, signinSecret), io, clock)
            SigninParts(signin, issuer, IdTokens(signin, issuer, clock))
        }
        return ServerListener(server, parts, tls, stores.admin, io, log, OptionalRoutes(signin, reach.mods))
    }
}

private const val SERVER_LOG_TAG = "server"
