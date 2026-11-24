// Server mode's HTTP/TLS engine: it binds the team's configured address (including non-loopback) and drains at stop.
package splice.server

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.http.listen.HttpListenerThreads
import splice.server.load.ServerLoad
import java.security.KeyStore
import java.util.concurrent.TimeUnit

internal class ServerListener(
    private val server: ServerConfig,
    private val tls: KeyStore?,
    private val load: ServerLoad,
    private val requests: ServerRequests,
    private val log: LogSink,
) {
    @Volatile
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Opens the listener on [address], `[server] listen` as boot read it. */
    fun start(address: ServerAddress) {
        val server = embeddedServer(
            Netty,
            serverConfig { module { intercept(ApplicationCallPipeline.Call) { requests.answer(call) } } },
        ) {
            HttpListenerThreads.configure(this)
            enableHttp2 = false
            if (tls == null) {
                connector {
                    host = address.host
                    port = address.port
                }
            } else {
                sslConnector(tls, PEM_ALIAS, { PEM_PASSWORD.toCharArray() }, { PEM_PASSWORD.toCharArray() }) {
                    host = address.host
                    port = address.port
                }
            }
            shutdownGracePeriod = SERVER_STOP_GRACE_MS
        }
        server.start(wait = false)
        engine = server
        val scheme = if (tls == null) "http" else "https"
        val commands = this.server.commands.joinToString(", ")
        log("[server] serving $commands to key holders on $scheme://${address.host}:${address.port}\n")
    }

    /** Drains, then closes the listener: new turns are refused and `/health` says draining from now, and the turns in
     *  flight are given up to `[server] drain_seconds` to end before the listener closes (#791). */
    suspend fun stop() {
        val engine = engine ?: return
        val drainMs = TimeUnit.SECONDS.toMillis(server.drainSeconds.toLong())
        if (!load.drain(drainMs) && drainMs > 0) {
            val left = load.inflight()
            log("[server] stop: $left turn(s) still in flight after the drain; they are cut\n")
        }
        engine.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
        this.engine = null
    }
}

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and its client's Claude Code retries it as it retries any cut turn.
private const val SERVER_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SERVER_STOP_TIMEOUT_MS = 5_000L
