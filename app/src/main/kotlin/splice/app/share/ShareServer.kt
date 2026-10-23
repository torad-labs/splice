// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). The one listener splice opens
// beyond loopback, and only when `[share] enabled`: the wall kt-embedded-server-loopback exempts this file
// alone, by that decision. Every request passes ShareNetwork first (private source, own Host); turns then
// need a joined machine's token ([guarded]) and are relayed to the head on loopback (ShareRelay). It never
// serves a management route, the console, or any head that `[share]` does not offer. Open by decision:
// what this machine offers (info) and joining it (trust the LAN, Marcos, 2026-10-22).
package splice.app.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads

internal class ShareServer(
    private val port: Int,
    private val heads: ShareHeads,
    private val clients: ShareClients,
    private val network: ShareNetwork,
    private val relay: ShareRelay,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val errors = ShareErrors()
    private val clientName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun start() {
        val server = embeddedServer(
            Netty,
            serverConfig {
                module {
                    intercept(ApplicationCallPipeline.Plugins) { if (!admitted(call)) finish() }
                    routing { routes(this) }
                }
            },
        ) {
            HttpListenerThreads.configure(this)
            connector {
                host = SHARE_BIND_HOST
                port = this@ShareServer.port
            }
            shutdownGracePeriod = SHARE_STOP_GRACE_MS
        }
        server.start(wait = false)
        engine = server
        log("[share] offering ${heads.keys()} on :$port to this machine's private networks\n")
    }

    fun stop() {
        engine?.stop(SHARE_STOP_GRACE_MS, SHARE_STOP_TIMEOUT_MS)
        engine = null
    }

    private suspend fun admitted(call: ApplicationCall): Boolean {
        val source = call.request.origin.remoteAddress
        val host = call.request.headers[HttpHeaders.Host]
        val reason = when {
            !network.admitsSource(source) -> "requests from $source are not from a private network"
            !network.admitsHost(host) -> "this Host is not one of this machine's names"
            else -> return true
        }
        refuse(call, HttpStatus.FORBIDDEN, reason)
        return false
    }

    private fun routes(root: Route) {
        root.get(SHARE_INFO_PATH) {
            call.respondText(json.encodeToString(ShareInfo.serializer(), heads.info()), ContentType.Application.Json)
        }
        root.post(SHARE_JOIN_PATH) { join(call) }
        root.route("$SHARE_HEADS_PATH/{head}") {
            // Any answer proves the path is there: what `splice add`'s reachability check asks of a base URL.
            get { call.respondText("splice share: head '${call.parameters["head"]}'\n") }
            post("/v1/messages") { if (guarded(call)) relayed(call, "/v1/messages") }
            post("/v1/messages/count_tokens") { if (guarded(call)) relayed(call, "/v1/messages/count_tokens") }
            get("/v1/models") { if (guarded(call)) relayed(call, "/v1/models") }
        }
    }

    /** True for a joined machine's token; anything else is answered here, with a 401 in words, and is false. */
    private suspend fun guarded(call: ApplicationCall): Boolean {
        val bearer = BearerScheme.bearerToken(call.request.headers[HttpHeaders.Authorization])
        if (clients.holder(bearer) != null) return true
        refuse(call, HttpStatus.UNAUTHORIZED, "this machine has not joined, or was revoked; run `splice remote` again")
        return false
    }

    /** Trust the LAN (Marcos, 2026-10-22): a private-network machine that asks is accepted with no code. */
    private suspend fun join(call: ApplicationCall) {
        val asked = Cancellables.runCatchingCancellable {
            json.decodeFromString(ShareJoin.serializer(), call.receiveText())
        }.fold(onSuccess = { it.client.trim() }, onFailure = { "" })
        if (!clientName.matches(asked)) {
            refuse(call, HttpStatus.BAD_REQUEST, "name the joining machine: {\"client\": \"<host name>\"}")
            return
        }
        val address = call.request.origin.remoteAddress
        val token = clients.join(asked, address)
        log("[share] '$asked' joined from $address\n")
        val joined = ShareJoined(heads.server, token)
        call.respondText(json.encodeToString(ShareJoined.serializer(), joined), ContentType.Application.Json)
    }

    private suspend fun relayed(call: ApplicationCall, path: String) {
        val head = call.parameters["head"].orEmpty()
        val port = heads.port(head)
        if (port == null) {
            refuse(call, HttpStatus.NOT_FOUND, "head '$head' is not offered by ${heads.server}")
            return
        }
        val query = call.request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        relay.relay(call, head, port, path + query)
    }

    private suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        call.respondText(errors.body(message), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }
}

/** Errors in the Anthropic shape, so Claude Code on the client prints the message instead of a bare status. */
internal class ShareErrors {
    fun body(message: String): String =
        """{"type":"error","error":{"type":"api_error","message":${JsonPrimitive("splice share: $message")}}}"""
}

// All interfaces: ShareNetwork decides who is answered, by source address and Host, before any route runs.
private const val SHARE_BIND_HOST = "0.0.0.0"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and the other machine's client retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L
