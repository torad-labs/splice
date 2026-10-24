// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). The one listener splice opens
// beyond loopback, and only when `[share] enabled`: the wall kt-embedded-server-loopback exempts this file
// alone, by that decision. Every request passes ShareNetwork first (private source, own Host); turns then
// need a joined machine's token ([guarded]) and are relayed to the head on loopback (ShareRelay). It never
// serves a management route, the console, or any head that `[share]` does not offer. Open by decision:
// what this machine offers (info) and joining it (trust the LAN, Marcos, 2026-10-22). Phase 2 adds, for joined
// machines only, each head's roster and plan windows and this machine's health (ShareStatus), and counts each
// guarded request toward that machine's last seen (ShareActivity). Milestone 6 adds the sessions of each side
// (ShareSessions), for joined machines only.
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.auth.BearerScheme
import splice.core.usage.QuotaJson
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads

internal class ShareServer(
    /** Where it listens, the certificate it serves (TLS only, since phase 2) and whom it answers. */
    private val endpoint: ShareEndpoint,
    private val heads: ShareHeads,
    private val clients: ShareClients,
    private val relay: ShareRelay,
    /** This machine's sessions and its mirrors of a joined machine's (ShareSessions). */
    private val sessions: ShareSessionHost,
    /** The client book's lock wait and file I/O block, so they run here, off the listener's two call threads
     *  (HttpListenerThreads), which every shared turn and info request needs free. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val errors = ShareErrors()
    private val clientName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** The heads it offers, and the fingerprint of the certificate it serves: what the Sharing page shows. */
    internal val offered: List<String> get() = heads.keys()
    internal val fingerprint: String get() = endpoint.tls.fingerprint

    internal fun start() {
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
            val tls = endpoint.tls
            sslConnector(tls.keyStore, SHARE_TLS_ALIAS, { tls.password }, { tls.password }) {
                host = SHARE_BIND_HOST
                port = endpoint.port
            }
            // A phase-1 client still speaking plain HTTP is answered in words before the TLS handler sees its bytes.
            // HTTP/1.1 only: with HTTP/2 on, Netty builds this pipeline after the ALPN handshake, so a plain request
            // never met the guard (checked 2026-10-23: an empty reply). Every share client speaks HTTP/1.1 to it.
            enableHttp2 = false
            channelPipelineConfig = { addFirst(SHARE_PLAIN_HANDLER, ShareTlsOnly(errors.body(PLAIN_REFUSAL))) }
            shutdownGracePeriod = SHARE_STOP_GRACE_MS
        }
        server.start(wait = false)
        engine = server
        val offered = "${heads.keys()} on :${endpoint.port} over TLS (${endpoint.tls.fingerprint})"
        log("[share] offering $offered to this machine's private networks\n")
    }

    fun stop() {
        engine?.stop(SHARE_STOP_GRACE_MS, SHARE_STOP_TIMEOUT_MS)
        engine = null
        sessions.withdraw()
        Cancellables.runCatchingCancellable { clients.activity.flush() }
            .onFailure { log("[share] last seen was not written: ${SafeFailureText.render(it)}\n") }
    }

    private suspend fun admitted(call: ApplicationCall): Boolean {
        val source = call.request.origin.remoteAddress
        val host = call.request.headers[HttpHeaders.Host]
        val reason = endpoint.network.refusal(source)
            ?: "this Host is not one of this machine's names".takeUnless { endpoint.network.admitsHost(host) }
            ?: return true
        refuse(call, HttpStatus.FORBIDDEN, reason)
        return false
    }

    private fun routes(root: Route) {
        root.get(SHARE_INFO_PATH) {
            call.respondText(json.encodeToString(ShareInfo.serializer(), heads.info()), ContentType.Application.Json)
        }
        root.post(SHARE_JOIN_PATH) { join(call) }
        root.get(SHARE_HEALTH_PATH) {
            guarded(call)?.let { respondJson(call, json.encodeToString(ShareHealth.serializer(), heads.health())) }
        }
        root.route("$SHARE_HEADS_PATH/{head}") {
            // Any answer proves the path is there: what `splice add`'s reachability check asks of a base URL.
            get { call.respondText("splice share: head '${call.parameters["head"]}'\n") }
            post("/v1/messages") { guarded(call, turn = true)?.let { relayed(call, "/v1/messages", it) } }
            post("/v1/messages/count_tokens") { guarded(call)?.let { relayed(call, "/v1/messages/count_tokens", it) } }
            get("/v1/models") { guarded(call)?.let { relayed(call, "/v1/models", it) } }
            get("/roster") { guarded(call)?.let { roster(call) } }
            get("/usage") { guarded(call)?.let { usage(call) } }
        }
        sessionRoutes(root)
    }

    /** This machine's sessions and a joined machine's, each route for a joined machine whose Sessions switch is on. */
    private fun sessionRoutes(root: Route) {
        root.route(SHARE_SESSIONS_PATH) {
            get { guarded(call, crossing = true)?.let { sessions.list(call, it) } }
            put { guarded(call, crossing = true)?.let { sessions.offer(call, it) } }
            get("/outbox") { guarded(call, crossing = true)?.let { sessions.outbox(call, it) } }
            post("/{id}/message") { guarded(call, crossing = true)?.let { sessions.note(call, it) } }
        }
    }

    /** The joined machine whose token this is, counted as heard from ([turn] counts a relayed turn); anything else is
     *  answered here, with a 401 in words, and is null. A route that [crossing] reaches this machine's sessions also
     *  needs that machine's Sessions switch on here (the President's decision on the round-1 review, Oct 23: trust the
     *  LAN for commands, not for sessions); one whose switch is off is answered with a 403 in words, and is null. */
    private suspend fun guarded(call: ApplicationCall, turn: Boolean = false, crossing: Boolean = false): String? {
        val bearer = BearerScheme.bearerToken(call.request.headers[HttpHeaders.Authorization])
        val holder = withContext(io) { clients.holder(bearer)?.also { clients.activity.heard(it.name, turn) } }
        val refused = when {
            holder == null ->
                HttpStatus.UNAUTHORIZED to "this machine has not joined, or was revoked; run `splice remote` again"
            crossing && !withContext(io) { sessions.crossing(holder.name) } ->
                HttpStatus.FORBIDDEN to "${heads.server} does not share its sessions with '${holder.name}': turn " +
                    "Sessions on for it on ${heads.server}'s Sharing page"
            else -> null
        }
        refused?.let { (status, why) -> refuse(call, status, why) }
        return holder?.name?.takeIf { refused == null }
    }

    private suspend fun roster(call: ApplicationCall) {
        val head = heads.head(call.parameters["head"].orEmpty())
        if (head == null) {
            refuse(call, HttpStatus.NOT_FOUND, "head '${call.parameters["head"]}' is not offered by ${heads.server}")
            return
        }
        respondJson(call, json.encodeToString(SharedHead.serializer(), head))
    }

    /** The head's plan windows in QuotaJson's shape; `{}` when it holds none, which reads back as no snapshot. */
    private suspend fun usage(call: ApplicationCall) {
        val quota = heads.quota(call.parameters["head"].orEmpty())
        respondJson(call, quota?.let { QuotaJson().encode(it) } ?: "{}")
    }

    private suspend fun respondJson(call: ApplicationCall, body: String) {
        call.respondText(body, ContentType.Application.Json)
    }

    /** Trust the LAN (Marcos, 2026-10-22): a private-network machine that asks is accepted with no code. Its body is
     *  one short name, read under a cap and a deadline of its own (PR #247's fourth review), never held whole first. */
    private suspend fun join(call: ApplicationCall) {
        val body = relay.body(call, SHARE_JOIN_BODY_MAX_BYTES, SHARE_JOIN_READ_MS) ?: return
        val asked = Cancellables.runCatchingCancellable {
            json.decodeFromString(ShareJoin.serializer(), body.decodeToString())
        }.fold(onSuccess = { it.client.trim() }, onFailure = { "" })
        if (!clientName.matches(asked)) {
            refuse(call, HttpStatus.BAD_REQUEST, "name the joining machine: {\"client\": \"<host name>\"}")
            return
        }
        val address = call.request.origin.remoteAddress
        // A book this machine cannot read or lock refuses the join and is left as it is (ShareClients.edited).
        val issued = withContext(io) { Cancellables.runCatchingCancellable { clients.join(asked, address) } }
        val token = issued.getOrElse { failure ->
            log("[share] '$asked' could not join from $address: ${SafeFailureText.render(failure)}\n")
            val why = "this machine could not update its list of joined machines; its log says why"
            refuse(call, HttpStatus.SERVICE_UNAVAILABLE, why)
            return
        }
        log("[share] '$asked' joined from $address\n")
        val joined = ShareJoined(heads.server, token)
        call.respondText(json.encodeToString(ShareJoined.serializer(), joined), ContentType.Application.Json)
    }

    private suspend fun relayed(call: ApplicationCall, path: String, client: String) {
        val head = call.parameters["head"].orEmpty()
        val port = heads.port(head)
        if (port == null) {
            refuse(call, HttpStatus.NOT_FOUND, "head '$head' is not offered by ${heads.server}")
            return
        }
        val query = call.request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        relay.relay(call, ShareTarget(head, port, path + query, client))
    }

    private suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        call.respondText(errors.body(message), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }
}

/** The share listener's [port], the certificate it serves there (ShareCertificate), and the [network] it answers. */
internal data class ShareEndpoint(val port: Int, val tls: ShareTls, val network: ShareNetwork)

/** Errors in the Anthropic shape, so Claude Code on the client prints the message instead of a bare status. */
internal class ShareErrors {
    fun body(message: String): String =
        """{"type":"error","error":{"type":"api_error","message":${JsonPrimitive("splice share: $message")}}}"""
}

// All interfaces: ShareNetwork decides who is answered, by source address and Host, before any route runs. IPv6
// clients reach it too: on a host with IPv6 the JDK opens an IPv6 socket and binds this wildcard as `::`, so `ss`
// shows `*:<port>` and `http://[::1]:<port>/share/v1/info` answers (PR #247's fifth review, checked on Linux).
private const val SHARE_BIND_HOST = "0.0.0.0"

// What a phase-1 client, which reached this listener over plain HTTP, is told: its machine moves onto TLS by importing
// this one again, which also moves its heads.
private const val PLAIN_REFUSAL =
    "this machine now shares over TLS only; run `splice remote` on the machine you are on to move its heads onto it"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and the other machine's client retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L

// why: a join's body is {"client": "<name>"} with a name of at most 64 characters; 4 KiB holds that with any JSON
// spacing, and nothing larger is read before it is refused.
private const val SHARE_JOIN_BODY_MAX_BYTES = 4_096

// why: a name that short arrives in one packet on a LAN; a join still unfinished after five seconds is refused
// instead of holding the listener open.
private const val SHARE_JOIN_READ_MS = 5_000L
