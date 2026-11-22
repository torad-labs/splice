// NEW: #525 — Ktor/Netty engine shell for ControlServer, pairing the listener with ActiveCallFence so stopping the
// control server unwinds active call coroutines while Netty's event loops are still alive.
package splice.app.control

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.ControlMount
import splice.http.listen.ActiveCallFence
import splice.http.listen.HttpListenerThreads

// ControlServer's lifecycle/limit constants, at their sanctioned file-scope home.
private const val STOP_GRACE_MS = 100L
private const val STOP_TIMEOUT_MS = 500L

// why: bound for cancelled control calls to unwind while Netty's event loop is still alive (#525).
private const val CONTROL_CALL_UNWIND_MS = 200L

internal class ControlEngine(
    private val listen: ControlListen,
    private val guard: ControlGuard,
    private val readings: ControlReadings,
    private val mounts: List<ControlMount>,
    private val callDispatcher: CoroutineDispatcher,
) {
    private val callFence = ActiveCallFence()

    fun open(): EmbeddedServer<NettyApplicationEngine, *> {
        callFence.open()
        return embeddedServer(
            Netty,
            serverConfig {
                module {
                    guard.admit(this)
                    guard.refuseForeignHosts(this)
                    intercept(ApplicationCallPipeline.Call) {
                        val job = coroutineContext[Job]
                        callFence.enter(job)
                        try {
                            withContext(callDispatcher) { proceed() }
                        } finally {
                            callFence.leave(job)
                        }
                    }
                    routing {
                        get("/health") { call.respondText(readings.health.json(), ContentType.Application.Json) }
                        mounts.forEach { it.register(this) }
                    }
                }
            },
        ) {
            HttpListenerThreads.configure(this)
            connector {
                host = "127.0.0.1"
                port = listen.port
            }
            // An inherited socket brings its own channel and parent handler (AdoptedBootstrap); the event loops are
            // the ones this engine builds for itself either way.
            listen.adopted?.let { adopted -> configureBootstrap = { adopted.applyTo(this) } }
            // Ktor disposes the application after the event loops and waits for its calls under this timeout, not the
            // one [stop] passes. Unset it is 5s, and a session's GET /mcp/{name} event stream never ends on its own,
            // so every stop waited all 5s here, after the heads' own 5s, past the daemon's 57s halt floor (Oct 19).
            shutdownGracePeriod = STOP_GRACE_MS
            shutdownTimeout = STOP_TIMEOUT_MS
            channelPipelineConfig = { pipeline -> guard.admit(pipeline) }
        }
    }

    fun stop(engine: EmbeddedServer<NettyApplicationEngine, *>?) {
        callFence.cancelAndAwait(CONTROL_CALL_UNWIND_MS)
        engine?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
    }
}
