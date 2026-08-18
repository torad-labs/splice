// PORT-OF: splice/gateway/head/HeadServer.kt (the Ktor/Netty bootstrap, the route table, the
// `server` field, RUNNING_LIMIT / WRITE_TIMEOUT_S / STOP_GRACE_MS / STOP_TIMEOUT_MS) @ 1caedd6 —
// invariants unchanged: the loopback-only connector, the running limit that keeps a thousand
// concurrent SSE turns off Netty's default queue, the response-write stall cap, and the
// once-per-start tcp_nodelay verification log. Split out (HD-24): this is the server shell half of
// the old file, and the only part of the head that knows Netty exists.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.netty.channel.socket.SocketChannelConfig
import splice.spi.Provider
import java.util.concurrent.atomic.AtomicBoolean

// Grace/timeout for Netty engine.stop after HeadServer's drain window.
private const val STOP_GRACE_MS = 500L
private const val STOP_TIMEOUT_MS = 2_000L

// Concurrent long-lived SSE turns per head (2x the 1000-stream design target).
private const val RUNNING_LIMIT = 2048

// Response-write stall cap. Netty's WriteTimeoutHandler ticks only while a write is
// PENDING — an upstream-idle lull writes nothing (bar the 10s keepalive pings), so this
// never races the watchdog's 180s streamIdle. A pending write that cannot complete in 60s
// means the client drained ZERO bytes for a full minute (coalesced frames clear in seconds
// on even a slow-but-alive pipe): that is a dead reader pinning an admission slot, not a
// slow one. The 300s bump quintupled dead-reader slot pinning for no live-client benefit
// (review 2026-07-22); 60s already carries 6x headroom over the default-10s load-test
// truncations (52/1000 stream tails).
private const val WRITE_TIMEOUT_S = 60

/** The head's Ktor/Netty listener: POST /v1/messages EXACTLY, POST /v1/messages/count_tokens,
 *  GET /v1/models (discovery-wrapped) and GET /health {ok,port,version}. */
internal class HeadEngine(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
    private val diagnostics: HeadDiagnostics,
    private val clientAuth: ClientAuth,
    private val admission: HeadAdmission,
    private val countTokens: CountTokens,
) {
    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        // G26: local (not a class field) so a control-plane restart (POST /api/heads/:head/restart)
        // re-arms verification instead of going permanently silent after the first restart.
        val nodelayLogged = AtomicBoolean(false)
        val engine = embeddedServer(
            Netty,
            serverConfig {
                module {
                    install(SSE)
                    routing {
                        get("/health") {
                            call.respondText(diagnostics.healthJson(), ContentType.Application.Json)
                        }
                        get("/v1/models") {
                            if (clientAuth.authorize(call)) {
                                call.respondText(diagnostics.modelsJson(), ContentType.Application.Json)
                            }
                        }
                        post("/v1/messages") { admission.handleMessages(call) }
                        // NAMED CHANGE: count_tokens gets a cheap dedicated handler, not the Node
                        // behavior (a real quota-burning turn). Local estimate keeps pre-flight cheap.
                        post("/v1/messages/count_tokens") { countTokens.handleCountTokens(call) }
                    }
                }
            },
        ) {
            connector {
                host = "127.0.0.1"
                port = listenPort
            }
            // Every call here is a LONG-LIVED SSE turn that occupies a running slot for its
            // whole life (seconds..minutes). Netty's default runningLimit (32) silently queues
            // the 33rd concurrent agent turn behind in-flight ones — a fleet of subagents feels
            // like the gateway "can barely hold a few". HeadServerLoadTest pins the ceiling at
            // >= 1000 concurrently-held streams.
            runningLimit = RUNNING_LIMIT
            // Default 10s killed stream TAILS during 1000-way completion bursts (load test:
            // 52/1000 truncated) — a write that waits on a busy client/kernel buffer is not a
            // dead stream. The watchdog owns real staleness; keep this as a last-resort cap.
            responseWriteTimeoutSeconds = WRITE_TIMEOUT_S
            // G26: verification-only (never sets a socket option) — fires on the first ACCEPTED
            // client connection with a real, already-connected socket (NettyChannelInitializer
            // .initChannel), logged once per start() so N connections don't spam the log.
            channelPipelineConfig = { pipeline ->
                if (nodelayLogged.compareAndSet(false, true)) {
                    val noDelay = (pipeline.channel().config() as? SocketChannelConfig)?.isTcpNoDelay
                    deps.log("[${provider.key}] tcp_nodelay(server)=${noDelay ?: "unknown"}\n")
                }
            }
        }
        engine.start(wait = false)
        server = engine
    }

    fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }
}
