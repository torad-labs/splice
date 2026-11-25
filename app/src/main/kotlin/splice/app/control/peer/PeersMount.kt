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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.PeerMcpLaunch
import splice.app.control.mount.ControlGuard
import splice.core.harness.HarnessCatalog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.http.JsonReply
import splice.launch.LaunchRecipe
import splice.launch.recipe.LaunchCaller
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.store.PeerLimits
import splice.sessions.peer.store.PeerStore
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
    private val catalog: HarnessCatalog? = null,
) {
    val harnesses: HarnessCatalog get() = catalog ?: services.services().harnesses
    val helpers: PeerHelpers? get() = services.services().helpers
    val mail: PeerMail? get() = services.services().mail
}

internal class PeersMount(parts: PeerParts, private val guard: ControlGuard) {
    private val rate = PeerSendRate()
    private val limits = PeerLimits(parts.store)
    private val manage = PeerManagementRoutes(parts, rate, limits)
    private val registration = PeerRegisterRoute(parts)
    private val webhooks = PeerWebhookRoutes(parts)
    private val self = PeerSelfRoutes(parts)
    private val sending = PeerSendRoute(parts, rate, limits)
    private val limit = PeerLimitRoute(parts, limits)
    private val codex = PeerCodexRoutes(parts)
    private val tools = PeerMcpTools(parts, sending, self)

    suspend fun launched(caller: LaunchCaller, recipe: LaunchRecipe): LaunchRecipe {
        if (!subscribe(caller)) return recipe
        return recipe.copy(argv = PeerMcpLaunch(EnvReader(System::getenv)).inFront(recipe.argv))
    }

    private suspend fun subscribe(caller: LaunchCaller): Boolean {
        val pid = caller.launch?.toLongOrNull() ?: return false
        val cwd = caller.cwd ?: return false
        return registration.subscribeClaude(pid, cwd)
    }

    fun register(route: Route) {
        route.post("/api/peers") { guard.guarded(call) { registration.register(call) } }
        route.post("/api/peers/codex") { guard.guarded(call) { codex.attach(call) } }
        route.delete("/api/peers/codex/{thread}") { guard.guarded(call) { manage.detachCodex(call) } }
        route.get("/api/peers") { guard.guarded(call) { manage.list(call) } }
        route.delete("/api/peers/{name}") { guard.guarded(call) { manage.revoke(call) } }
        route.post("/api/peers/{name}/rotate") { guard.guarded(call) { manage.rotate(call) } }
        route.put("/api/peers/{name}/limit") { guard.guarded(call) { limit.set(call) } }
        route.put("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.set(call) } }
        route.delete("/api/peers/{name}/webhook") { guard.guarded(call) { webhooks.clear(call) } }
        route.post("/api/peers/{name}/webhook/test") { guard.guarded(call) { webhooks.test(call) } }
        route.post("/api/peers/self/send") { guard.peer.guarded(call) { peer -> sending.send(call, peer) } }
        route.get("/api/peers/self/inbox") { guard.peer.guarded(call) { peer -> self.inbox(call, peer) } }
        route.post("/api/peers/self/inbox/ack") { guard.peer.guarded(call) { peer -> self.ack(call, peer) } }
        route.get("/api/peers/self/sent/{msgId}") { guard.peer.guarded(call) { peer -> self.sent(call, peer) } }
        route.get("/api/peer/list") { guard.guarded(call) { manage.list(call) } }
        route.post("/api/peer/send") { guard.peer.guarded(call) { peer -> sending.send(call, peer) } }
        route.get("/api/peer/status/{msgId}") { guard.peer.guarded(call) { peer -> self.sent(call, peer) } }
        route.post(PEER_MCP_ENDPOINT) { guard.peer.guarded(call) { peer -> tools.post(call, peer) } }
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

    fun unavailable(status: HttpStatusCode, reason: String): PeerAnswer =
        PeerAnswer(
            status,
            buildJsonObject {
                put("status", "unavailable")
                put("reason", reason)
                put("error", reason)
            },
        )

    fun ok(status: HttpStatusCode, body: JsonObject): PeerAnswer = PeerAnswer(status, body)

    fun string(body: JsonObject?, key: String): String? =
        (body?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
