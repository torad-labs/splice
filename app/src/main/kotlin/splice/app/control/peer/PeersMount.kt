// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 3): the /api/peers routes. The management routes register, list,
// revoke and rotate peers and set their webhooks, behind the management key. The /api/peers/self routes act as one
// peer, behind that peer's own key (Door.PEER): they send under its name, and read and acknowledge its journal.
// Nothing a request carries names the sender: it is the principal the key opened, and the socket its helper runs on.
package splice.app.control.peer

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.control.mount.ControlGuard
import splice.core.config.UserHome
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.http.JsonBody
import splice.http.JsonReply
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.store.PeerRefusal
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource

internal const val PEER_NAME_PARAM = "name"
internal const val NO_PEER = "no peer has that name"
internal const val PEER_MAIL_UNWIRED = "peer messaging is not available in this build"
internal const val HELPER_NOT_RUNNING = "this peer's helper is not running"

/** The parts the routes act through. [helpers] and [mail] are read from [services] at each request, so the daemon can
 *  wire them after the server is built; a route that needs one that is absent answers 503. */
internal data class PeerParts(
    val store: PeerStore,
    val registry: SessionSource,
    val outbox: PeerOutbox?,
    val services: PeerServiceSource,
    val log: LogSink,
) {
    val helpers: PeerHelpers? get() = services.services().helpers
    val mail: PeerMail? get() = services.services().mail
}

internal class PeersMount(parts: PeerParts, private val guard: ControlGuard) {
    private val rate = PeerSendRate()
    private val manage = PeerManagementRoutes(parts, rate)
    private val webhooks = PeerWebhookRoutes(parts)
    private val self = PeerSelfRoutes(parts, rate)
    private val codex = PeerCodexRoutes(parts)

    fun register(route: Route) {
        route.post("/api/peers") { guard.guarded(call) { manage.register(call) } }
        route.post("/api/peers/codex") { guard.guarded(call) { codex.attach(call) } }
        route.get("/api/peers") { guard.guarded(call) { manage.list(call) } }
        route.delete("/api/peers/{name}") { guard.guarded(call) { manage.revoke(call) } }
        route.post("/api/peers/{name}/rotate") { guard.guarded(call) { manage.rotate(call) } }
        route.put("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.set(call) } }
        route.delete("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.clear(call) } }
        route.post("/api/peers/{name}/webhook/test") { guard.guarded(call) { webhooks.test(call) } }
        route.post("/api/peers/self/send") { guard.peer.guarded(call) { peer -> self.send(call, peer) } }
        route.get("/api/peers/self/inbox") { guard.peer.guarded(call) { peer -> self.inbox(call, peer) } }
        route.post("/api/peers/self/inbox/ack") { guard.peer.guarded(call) { peer -> self.ack(call, peer) } }
        route.get("/api/peers/self/sent/{msgId}") { guard.peer.guarded(call) { peer -> self.sent(call, peer) } }
    }
}

/** One route's answer: its status, its JSON body, and the `Retry-After` seconds a 429 carries. */
internal data class PeerAnswer(val status: HttpStatusCode, val body: JsonObject, val retryAfter: Long? = null) {
    suspend fun send(call: ApplicationCall) {
        retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
        JsonReply(status, body.toString()).send(call)
    }
}

/** The answers both route families share. */
internal object PeerReplies {
    fun refuse(status: HttpStatusCode, reason: String, retryAfter: Long? = null): PeerAnswer =
        PeerAnswer(status, buildJsonObject { put("error", reason) }, retryAfter)

    fun ok(status: HttpStatusCode, body: JsonObject): PeerAnswer = PeerAnswer(status, body)

    fun string(body: JsonObject?, key: String): String? =
        (body?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The management-key routes that make, list, revoke and rotate peers (spec 3.2). */
internal class PeerManagementRoutes(private val parts: PeerParts, private val rate: PeerSendRate) {
    private val store = parts.store
    private val jsonBody = JsonBody()

    /** POST /api/peers: writes peer.json and peer.key, starts the helper, and answers once its record is on disk. */
    suspend fun register(call: ApplicationCall) {
        registered(jsonBody.parse(call)).send(call)
    }

    /** GET /api/peers: every peer, whether its helper runs, its scope, webhook and inbox counts. */
    suspend fun list(call: ApplicationCall) {
        val peers = store.list().map { config -> describe(config) }
        PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("peers", JsonArray(peers)) }).send(call)
    }

    /** DELETE /api/peers/{name}: drops the key first, then stops the helper; the journal stays unless purge=true. */
    suspend fun revoke(call: ApplicationCall) {
        val name = call.parameters[PEER_NAME_PARAM].orEmpty()
        val purge = call.request.queryParameters["purge"] == "true"
        revoked(name, purge).send(call)
    }

    /** POST /api/peers/{name}/rotate: a new peer.key; the old key fails at once. */
    suspend fun rotate(call: ApplicationCall) {
        val name = call.parameters[PEER_NAME_PARAM].orEmpty()
        val keyFile = if (store.wellFormed(name)) store.rotate(name) else null
        val answer = if (keyFile == null) {
            PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        } else {
            parts.log("[peers] rotated the key of ${LogSafe.str(name)}\n")
            PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("keyFile", keyFile.toString()) })
        }
        answer.send(call)
    }

    private suspend fun registered(body: JsonObject?): PeerAnswer {
        val name = PeerReplies.string(body, "name")
        val cwd = if (body?.containsKey("cwd") == true) PeerReplies.string(body, "cwd") else UserHome.dir().toString()
        val sendTo = sendTo(body)
        if (name == null) return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"name\": \"<name>\"}")
        if (cwd == null || sendTo == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "cwd is a path and sendTo a list of names")
        }
        val live = parts.registry.read().filter { it.availability != SessionAvailability.GONE }
        val liveNames = live.mapNotNull { it.name }.toSet()
        return when (val outcome = store.register(PeerRequest(name, cwd, sendTo), liveNames)) {
            is PeerRegistration.Refused -> PeerReplies.refuse(
                if (outcome.why == PeerRefusal.INVALID) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
                outcome.reason,
            )
            is PeerRegistration.Registered -> {
                val pid = parts.helpers?.start(name)
                parts.log("[peers] registered ${LogSafe.str(name)}\n")
                PeerReplies.ok(
                    HttpStatusCode.Created,
                    buildJsonObject {
                        put("name", name)
                        put("sessionId", outcome.config.sessionId)
                        put("keyFile", outcome.keyFile.toString())
                        put("socket", parts.helpers?.running(name)?.socket)
                        put("pid", pid)
                    },
                )
            }
        }
    }

    private suspend fun revoked(name: String, purge: Boolean): PeerAnswer {
        if (!store.wellFormed(name) || !store.revoke(name)) return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        rate.forget(name)
        parts.helpers?.stop(name)
        val mail = parts.mail
        val purged = purge && mail != null
        if (purged) mail.purge(name)
        parts.log("[peers] revoked ${LogSafe.str(name)}\n")
        return PeerReplies.ok(
            HttpStatusCode.OK,
            buildJsonObject {
                put("revoked", name)
                put("purged", purged)
            },
        )
    }

    private fun describe(config: PeerConfig): JsonObject {
        val helper = parts.helpers?.running(config.name)
        val counts = parts.mail?.counts(config.name)
        val webhook = config.webhook
        return buildJsonObject {
            put("name", config.name)
            put("sessionId", config.sessionId)
            put("pid", helper?.pid)
            put("alive", helper != null)
            put("socket", helper?.socket)
            putJsonArray("sendTo") { config.sendTo.forEach { add(JsonPrimitive(it)) } }
            if (webhook == null) {
                put("webhook", JsonNull)
            } else {
                putJsonObject("webhook") {
                    put("url", webhook.url)
                    put("enabled", webhook.enabled)
                    put("lastOutcome", parts.mail?.lastWebhookOutcome(config.name))
                }
            }
            putJsonObject("inbox") {
                put("total", counts?.total ?: 0L)
                put("unread", counts?.unread ?: 0L)
            }
        }
    }

    /** `sendTo` as given, or `["*"]` when absent; null when it is there but not a list of strings. */
    private fun sendTo(body: JsonObject?): List<String>? {
        val raw = body?.get("sendTo") ?: return listOf("*")
        val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return items?.takeIf { list -> list.none { it == null } }?.filterNotNull()
    }
}
