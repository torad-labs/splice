// NEW: LAYOUT-01 — the surface the control plane itself owns: the status payload, the head list with its per-head
// actions, each head's log tail and the whole daemon log's. Liveness (/health) is the control server's own row, not
// this mount's.
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.api.ControlAudit
import splice.app.control.api.HeadResolver
import splice.app.control.api.fleet.HeadRoutes
import splice.core.util.JsonScalars
import splice.core.wire.ControlFields.KEY
import splice.core.wire.ControlFields.LABEL
import splice.diagnostics.logs.HeadLogSource
import splice.diagnostics.logs.LOG_MAX_LINES
import splice.heads.HeadStatusListing
import splice.heads.ListHeads
import splice.sessions.http.StartCommand

// why: the log lines /api/logs/{head} returns when the request names no ?tail — the recent end of the
// file the console shows first, never the whole file.
private const val DEFAULT_LOG_TAIL = 200

internal class FleetMount(
    private val heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    audit: ControlAudit,
    private val guard: ControlGuard,
    /** Read at CALL time for the declared roster's families: ControlPlane assigns it after construction. */
    private val ports: ConsolePorts,
    /** daemon.log with no head filter, for /api/logs: the lines no head tag reaches, startup and the hub among them. */
    private val daemonLog: HeadLogSource,
) {
    private val headRoutes = HeadRoutes(resolver, audit)
    private val starts = HeadStartCommands(heads)

    /** Each head's status, and for a launchable head whose command cannot start a session the refusal as `cannotStart`,
     *  so Continue on and a team start say it before the press rather than after it (walk p180). */
    private val listHeads = ListHeads(
        HeadStatusListing {
            resolver.headStatuses().map { status ->
                val key = JsonScalars.str(status, KEY)
                val managed = key?.let(heads::get)?.takeIf { it.launchSpec != null }
                val refused = managed?.let { starts.forHead(it.head.key) as? StartCommand.Refused }
                if (refused == null) status else JsonObject(status + ("cannotStart" to JsonPrimitive(refused.reason)))
            }
        },
    )

    /** The /api/heads body, for the daemon's own doctor (V4-230). */
    fun headsJson(): String = listHeads.json()

    fun register(route: Route) {
        route.get("/api/status") {
            guard.guarded(call) { ControlReplies.respond(call, statusJson(families())) }
        }
        route.get("/api/heads") { guard.guarded(call) { listHeads.handle(call) } }
        route.post("/api/heads/{head}/{action}") { guard.guarded(call) { headRoutes.headAction(call) } }
        route.get("/api/logs") { guard.guarded(call) { ControlReplies.respond(call, daemonLogJson(call)) } }
        route.get("/api/logs/{head}") {
            guard.guarded(call) {
                headRoutes.logsJson(call, ControlReplies.tail(call, DEFAULT_LOG_TAIL, LOG_MAX_LINES))
            }
        }
    }

    /** The /api/logs body: the unfiltered tail, in /api/logs/{head}'s shape without a key. */
    private fun daemonLogJson(call: ApplicationCall): String {
        val lines = daemonLog.tail(ControlReplies.tail(call, DEFAULT_LOG_TAIL, LOG_MAX_LINES)).split("\n")
        return buildJsonObject {
            put("path", daemonLog.path())
            putJsonArray("lines") { lines.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    /** The /api/status body: the registry rows, each with its family from the declared roster. */
    private fun statusJson(families: Map<String, String?>): String = buildJsonObject {
        putJsonArray("registry") {
            heads.values.forEach { m ->
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    put("authKind", m.authSurface.authKind)
                    put("family", families[m.head.key])
                }
            }
        }
    }.toString()

    /** Each head's family from the declared roster; an unwired roster is no families, never a 5xx,
     *  because the status read every page polls must not fail over a colour. */
    private fun families(): Map<String, String?> =
        ports.declaredHeads?.invoke()?.mapValues { (_, declared) -> declared.family }.orEmpty()
}
