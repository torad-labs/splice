// Builds the team server's HTTP/TLS listener and OIDC sign-in parts from ServerMode's stores.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.ServerConfig
import splice.core.topology.ServerSignin
import splice.core.util.EnvReader
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

internal data class ListenerSignins(val cli: SigninParts?, val browser: SigninParts?, val clock: Clock)

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
        val cli = server.signin?.let { signin(it, signinSecret) }
        val browser = browserSignin(cli, reach.env)
        return ServerListener(server, parts, tls, stores.admin, io, log, ListenerSignins(cli, browser, clock))
    }

    private fun signin(policy: ServerSignin, secret: String?): SigninParts {
        val issuer = SigninIssuer(SigninClient(policy, secret), io, clock)
        return SigninParts(policy, issuer, IdTokens(policy, issuer, clock))
    }

    private fun browserSignin(cli: SigninParts?, env: EnvReader): SigninParts? {
        val auth = server.forwardAuth?.takeIf { it.browserOrigin != null } ?: return null
        val clientId = auth.clientId ?: return cli
        val secret = auth.clientSecretEnv?.let { name ->
            requireNotNull(env(name)?.takeIf(String::isNotBlank)) {
                "server.forward_auth.client_secret_env names an unset or empty variable"
            }
        }
        return signin(checkNotNull(cli).signin.copy(clientId = clientId), secret)
    }
}

private const val SERVER_LOG_TAG = "server"
