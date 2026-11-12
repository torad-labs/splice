// NEW: LAYOUT-01 — the management-key check every control route runs behind, lifted out of ControlServer
// so each capability's mount guards its own rows with the one check rather than a copy of it. A route
// that throws past the check is answered by RouteFailure (2026-09-23), in the daemon log and in its 500.
// The v0.4.0 scoped doors and the foreign-Host refusal (d2a22c31, 94e9d705) landed here from
// ControlServer, where they were written before the split.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.netty.channel.ChannelPipeline
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.MgmtRoute
import splice.app.control.api.ControlAudit
import splice.app.control.api.RouteFailure
import splice.client.mcp.McpAccessKey
import splice.core.auth.ForeignHostLog
import splice.core.auth.LoopbackHost
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.http.ingress.HeapIngress
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.store.PeerStore

/** Which scoped bearer a route admits BESIDE the management key, which opens every route but [PEER]'s. One door
 *  per route, so no route can be widened to two scoped keys by a flag pair. */
internal enum class Door { MANAGEMENT, MCP, SESSION, PEER }

/** The body of one `/api/peers/self` route, run only after the bearer matched a peer's own key. It acts as, and
 *  names as the sender, [PeerPrincipal] and nobody else. */
internal fun interface PeerRoute {
    suspend operator fun invoke(principal: PeerPrincipal)
}

/**
 * [Door.PEER] (docs/specs/peer-messaging.md 3.1): admits only a bearer whose sha256 is some peer's key hash, through
 * [PeerStore]'s map, and hands the route that peer.
 *
 * The management key is refused here with 403. Why this is the one route family the management key does not open:
 * these routes act AS a peer and write its name as the sender of a message, and the management key is no peer, so
 * opening them to it would let it send under any peer's name. The other doors never consult [PeerStore], so a peer
 * key anywhere else is a wrong key, 401.
 */
internal class PeerGuard(
    private val mgmtKey: MgmtKey,
    private val peers: PeerStore?,
    private val routeFailure: RouteFailure,
) {
    suspend fun guarded(call: ApplicationCall, block: PeerRoute) {
        val header = call.request.headers["Authorization"]
        val principal = if (mgmtKey.matchesBearer(header)) null else peers?.principal(header)
        when {
            principal != null ->
                Cancellables.runCatchingBestEffort { block(principal) }.onFailure { routeFailure.answer(call, it) }
            mgmtKey.matchesBearer(header) -> refuse(call, HttpStatusCode.Forbidden, "use the peer's own key")
            else -> refuse(call, HttpStatusCode.Unauthorized, "unauthorized")
        }
    }

    private suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, error: String) {
        call.respondText(buildJsonObject { put("error", error) }.toString(), ContentType.Application.Json, status)
    }
}

/** Runs [MgmtRoute] only after the bearer matched the management key or the key of the route's [Door]:
 *  the MCP access key opens only the /mcp/{name} routes, and a launched session's turn key only the
 *  routes its own hooks call. The console key ([ConsoleKey], Oct 26, 2026) opens what the management key
 *  opens here, so a browser never holds the management key itself; like it, it opens no [Door.PEER] row. */
internal class ControlGuard(
    private val mgmtKey: MgmtKey,
    audit: ControlAudit,
    log: LogSink,
    /** The request-size and heap admission, built by ControlPlane from the daemon's one request cap. */
    private val ingress: HeapIngress,
    /** Production supplies the persisted source; source-only fixtures retain their bootstrap derivation. */
    internal val mcpAccessKey: McpAccessKey = McpAccessKey(source = mgmtKey::get),
    /** The registered peers whose keys open [Door.PEER]; null admits no peer key at all. */
    internal val peers: PeerStore? = null,
    /** What the console holds instead of the management key, and the single-use codes `splice console` hands it. */
    internal val console: ConsoleKey = ConsoleKey(mgmtKey),
) {

    // v0.4.0: the credential a launched session holds opens its OWN hooks' routes and nothing else.
    private val turnKey = TurnKey(mgmtKey)
    private val routeFailure = RouteFailure(audit)

    /** [Door.PEER]'s check: `guard.peer.guarded(call) { principal -> … }` on every `/api/peers/self` route. */
    val peer: PeerGuard = PeerGuard(mgmtKey, peers, routeFailure)
    private val foreignHosts = ForeignHostLog("the control plane", log)

    /** v0.4.0: a request naming a non-loopback Host is a DNS-rebinding page in the operator's browser
     *  (see LoopbackHost). Refused before routing, so no route — guarded or open — runs for it, and
     *  said in the daemon log once per name (ForeignHostLog). */
    fun refuseForeignHosts(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val host = call.request.headers[HttpHeaders.Host]
            if (!LoopbackHost.admits(host)) {
                foreignHosts.refused(host.orEmpty())
                call.respondText(
                    buildJsonObject { put("error", LoopbackHost.FOREIGN_HOST_REFUSAL) }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Forbidden,
                )
                finish()
            }
        }
    }

    /** Admits a request's body against the heap budget ahead of routing, on the module the server installs. */
    fun admit(app: Application) {
        ingress.install(app)
    }

    /** The same admission on the channel pipeline, where the engine reads bytes before any module runs. */
    fun admit(pipeline: ChannelPipeline) {
        ingress.install(pipeline)
    }

    /** Mints the management key now, before the port opens, so a dashboard load never races the minting. */
    fun mintKey(): String = mgmtKey.get()

    /** [keylessNotice] answers a call that carries NO Authorization header at all with a 200 and that one line,
     *  instead of the JSON refusal: a status line command from a session launched before 0.4.0 sends no
     *  bearer, and the line it prints is the only place the user would see what to do. The door stays shut: a
     *  wrong key is still refused, and the notice carries no data. */
    suspend fun guarded(
        call: ApplicationCall,
        door: Door = Door.MANAGEMENT,
        keylessNotice: String? = null,
        block: MgmtRoute,
    ) {
        // A PEER route run through here still gets the peer door, never the management key's.
        if (door == Door.PEER) return peer.guarded(call) { block() }
        val header = call.request.headers["Authorization"]
        if (header == null && keylessNotice != null) {
            call.respondText(keylessNotice, ContentType.Text.Plain)
            return
        }
        if (!admits(header, door)) {
            call.respondText(
                buildJsonObject { put("error", "unauthorized") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return
        }
        Cancellables.runCatchingBestEffort { block() }.onFailure { routeFailure.answer(call, it) }
    }

    /** The management key and the console key open every [door] that reaches here; a scoped key opens only its own. */
    private fun admits(header: String?, door: Door): Boolean = mgmtKey.matchesBearer(header) || when (door) {
        Door.MANAGEMENT, Door.PEER -> false
        Door.MCP -> mcpAccessKey.matchesBearer(header)
        Door.SESSION -> turnKey.matchesBearer(header)
    } || console.matchesBearer(header)
}
