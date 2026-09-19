// PORT-OF: server/src/control/api.mjs + control-server.mjs @ pre-public-port-baseline — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head + the committed
// single-file dashboard at /. Single-daemon simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state. JSON payload shapes match webui/src/shared/api/index.ts so the
// unmodified dashboard runs against this daemon (the P4-WEBUI contract).
//
// HD-24: split into splice.control.api (the HTTP surface — payload projections and by-name
// routes) + splice.control (this file: ctor/routing/lifecycle, plus ManagedHead/LaunchService/
// LaunchResponse/StatuslineRenderer/ControlPorts). Same-package siblings were arithmetically
// insufficient (a floor well above what this file's remaining budget allows), so the split is one
// level deeper. One direction of real dependency: api -> domain.
package splice.control

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.api.ActivitySource
import splice.control.api.AuthRoutes
import splice.control.api.CompactPayloads
import splice.control.api.CompactionInstructionsRoute
import splice.control.api.ConfigRoutes
import splice.control.api.ControlAudit
import splice.control.api.ControlPayloads
import splice.control.api.DaemonRoutes
import splice.control.api.DoctorRoute
import splice.control.api.EconomicsPayloads
import splice.control.api.EventsRoute
import splice.control.api.HeadResolver
import splice.control.api.HeadRoutes
import splice.control.api.JsonBody
import splice.control.api.LaunchRoutes
import splice.control.api.McpRoutes
import splice.control.api.ModelsRoute
import splice.control.api.PerfPayloads
import splice.control.api.PerfRoutes
import splice.control.api.ProjectsRoutes
import splice.control.api.RepoOf
import splice.control.api.SentTextSource
import splice.control.api.SessionsRoutes
import splice.control.api.StatuslineRoute
import splice.control.api.TeamSource
import splice.control.api.TeamsRoutes
import splice.control.api.TopologyRoutes
import splice.control.api.TopologySource
import splice.control.api.UpgradeRoute
import splice.control.api.UsagePayloads
import splice.control.mcp.McpHost
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.launch.McpAccessKey
import splice.core.sessions.SessionRegistry
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker

// ControlServer's lifecycle/limit constants, at their sanctioned file-scope home.
private const val STOP_GRACE_MS = 100L
private const val STOP_TIMEOUT_MS = 500L
private const val DEFAULT_LOG_TAIL = 200
private const val DEFAULT_PERF_TAIL = 200
private const val MAX_TAIL = 2_000

/** V4-134: answered on /api/events until ControlPlane assigns [ConsolePorts.events]. NOT an empty
 *  stream: a stream that opens and stays quiet reads as a daemon with nothing happening, which is a
 *  confident false negative about a daemon serving turns right now. The text names what was not done. */
private const val EVENTS_UNWIRED = "the daemon wired no console event bus; /api/events cannot stream its events"

public class ControlServer(
    private val port: Int,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: DashboardPage,
    private val log: LogSink,
    private val launchService: LaunchService? = null,
    private val shutdownDaemon: ShutdownDaemon = ShutdownDaemon {},
    // Live count of heads that failed to assemble or start (Daemon.start's `failed` map) — lets
    // the /health readyHeads protocol converge on a degraded boot instead of waiting forever for
    // a head that will never become ready (review 2026-07-22 round 3).
    private val failedHeads: FailedHeads = FailedHeads { 0 },
    // Total CONFIGURED heads (topology). The readyHeads + failedHeads == heads invariant only holds
    // against the configured total: an assembly-failed head is counted in failedHeads but is NEVER
    // in the `heads` map, so reporting heads.size broke the invariant for it (review 2026-07-23).
    private val configuredHeads: Int = heads.size,
    // JW-04: the booted config identity + a per-request staleness recompute (fail-open lambda).
    // Topology stays deliberately non-hot-reloadable; these only make the required restart VISIBLE
    // to the shim, doctor, and the dashboard.
    private val topologyDigest: String = "",
    private val configPath: String = "",
    private val topologyStale: TopologyStale = TopologyStale { false },
    private val turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
    /** v0.4.0 shared MCP hosting; null keeps the control plane exactly as before. */
    private val mcpHost: McpHost? = null,
    /** v0.4.0 (FEATURES.md §4): the Claude Code session registry, read-only, for /api/sessions. */
    sessions: SessionRegistry? = null,
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
) {
    /** The nine ports ControlPlane wires after construction — see [ConsolePorts], which carries the
     *  discipline they share and why they left this file (V4-161). Read at CALL time, never captured. */
    public val ports: ConsolePorts = ConsolePorts()

    private val mcpAccessKey = McpAccessKey(mgmtKey::get)

    private val sessionsRoutes = sessions?.let {
        SessionsRoutes(it, heads, config, ActivitySource { ports.activity }, teams = TeamSource { ports.teams })
    }
    private val teamsRoutes = sessionsRoutes?.let { routes ->
        TeamsRoutes(
            TeamSource { ports.teams },
            heads,
            sessions,
            ActivitySource { ports.activity },
            SentTextSource(routes::sentTexts),
        )
    }
    private val projectsRoutes = sessionsRoutes?.let { routes ->
        ProjectsRoutes(sessions, heads, RepoOf(routes::repoOf), TeamSource { ports.teams })
    }
    private val payloads =
        ControlPayloads(
            heads,
            failedHeads,
            configuredHeads,
            topologyDigest,
            configPath,
            topologyStale,
            turnPathStalled,
            clientVersions,
        )
    private val resolver = HeadResolver(heads, payloads)

    /** Reads [compaction] at CALL time through a lambda: ControlPlane assigns the property after
     *  the server is constructed, so a route that captured the value would capture null forever. */
    private val compactionRoute = CompactionInstructionsRoute(resolver)

    private val topologyRoutes = TopologyRoutes(TopologySource { ports.topology }, topologyStale)

    private val daemonRoutes = DaemonRoutes()
    private val modelsRoute = ModelsRoute(heads)
    private val perfRoutes = PerfRoutes(resolver)
    private val doctorRoute = DoctorRoute()
    private val upgradeRoute = UpgradeRoute()
    private val jsonBody = JsonBody()
    private val audit = ControlAudit(log)
    private val configRoutes = ConfigRoutes(config, jsonBody, payloads)
    private val usagePayloads = UsagePayloads(heads, config)
    private val perfPayloads = PerfPayloads(heads)
    private val economicsPayloads = EconomicsPayloads(heads)
    private val compactPayloads = CompactPayloads(heads)
    private val authRoutes = AuthRoutes(heads, resolver)
    private val headRoutes = HeadRoutes(resolver, payloads, audit)
    private val launchRoutes = LaunchRoutes(heads, resolver, launchService, payloads, audit, jsonBody)
    private val statuslineRoute = StatuslineRoute(resolver, config, clientVersions)
    private val mcpRoutes = mcpHost?.let(::McpRoutes)

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    public fun start() {
        mgmtKey.get() // mint eagerly BEFORE the port opens — a dashboard load must not race it
        val engine = controlEngine()
        engine.start(wait = false)
        server = engine
        mcpHost?.start()
    }

    /** The engine and the whole route table it serves. Extracted from [start] (V4-136): the route
     *  table has grown a row at a time and finally tripped the method-length wall, which was
     *  measuring the TABLE rather than the startup sequence — two different jobs that never belonged
     *  in one body. Adding a route should not be a reason to restructure startup, or the reverse. */
    private fun controlEngine(): EmbeddedServer<NettyApplicationEngine, *> =
        embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                // Unauthenticated liveness probe: the launch shim polls this to tell a running
                // daemon from a cold start (it must NOT need the mgmt-key). No head/config detail.
                get("/health") { call.respondText(payloads.controlHealthJson(), ContentType.Application.Json) }
                get("/") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
                get("/dashboard") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
                get("/api/status") { guarded(call) { respond(call, payloads.statusJson()) } }
                get("/api/heads") { guarded(call) { respond(call, resolver.headsJson()) } }
                post("/api/heads/{head}/{action}") { guarded(call) { headRoutes.headAction(call) } }
                post("/api/daemon/shutdown") {
                    guarded(call) {
                        call.respondText(payloads.okJson(), ContentType.Application.Json, HttpStatusCode.Accepted)
                        shutdownDaemon()
                    }
                }
                get("/api/config") {
                    // JW-06: ?head=<key> folds that head's override layer into `effective`.
                    guarded(call) { respond(call, configRoutes.configJson(call.request.queryParameters["head"])) }
                }
                patch("/api/config") { guarded(call) { configRoutes.patchConfig(call) } }
                get("/api/usage") { guarded(call) { respond(call, usagePayloads.usageJson()) } }
                get("/api/perf") {
                    guarded(call) { respond(call, perfPayloads.perfJson(tail(call, DEFAULT_PERF_TAIL))) }
                }
                get("/api/perf/summary") { guarded(call) { perfPayloads.summary(call) } }
                get("/api/economics") { guarded(call) { respond(call, economicsPayloads.economicsJson()) } }
                get("/api/auth") { guarded(call) { respond(call, authRoutes.authJson()) } }
                post("/api/auth/{head}/{action}") { guarded(call) { authRoutes.authAction(call) } }
                get("/api/compact") { guarded(call) { respond(call, compactPayloads.compactJson()) } }
                sessionsRoutes?.let { sessionRoutes(this, it) }
                get("/api/logs/{head}") { guarded(call) { headRoutes.logsJson(call, tail(call, DEFAULT_LOG_TAIL)) } }
                // V4-126: additive. Every poll route above is untouched and stays the fallback.
                get("/api/events") { guarded(call) { streamEvents(call) } }
                // V4-136: additive too. ?head=<key> is REQUIRED and an unknown one is a 400 naming
                // it, never a 404 — the console reads 404 on this path as route-not-built.
                get("/api/compaction/instructions") {
                    guarded(call) { compactionRoute.instructions(call, ports.compaction) }
                }
                consoleRoutes(this)
                post("/launch/{head}") { guarded(call) { launchRoutes.launch(call) } }
                post("/statusline/{head}") { guarded(call) { statuslineRoute.statusline(call) } }
                get("/statusline/{head}") { guarded(call) { statuslineRoute.statusline(call) } }
                if (mcpRoutes != null && mcpHost != null) {
                    get("/api/mcp") { guarded(call) { respond(call, mcpHost.statusJson()) } }
                    post("/mcp/{name}") { guarded(call, mcp = true) { mcpRoutes.post(call) } }
                    get("/mcp/{name}") { guarded(call, mcp = true) { mcpRoutes.stream(call) } }
                    delete("/mcp/{name}") { guarded(call, mcp = true) { mcpRoutes.delete(call) } }
                }
            }
        }

    /** v0.4.0 /api/sessions, V4-130's three session reads beside it, and V4-131's team and project
     *  routes, which read the same registry. Registered only when a session registry is wired, as
     *  /api/sessions always was. */
    private fun sessionRoutes(route: Route, routes: SessionsRoutes) {
        route.get("/api/sessions") { guarded(call) { respond(call, routes.sessionsJson()) } }
        route.get("/api/sessions/edges") { guarded(call) { routes.edgeRoutes.boardEdges().send(call) } }
        route.get("/api/sessions/{id}/edges") {
            guarded(call) { routes.edgeRoutes.edges(call.parameters["id"].orEmpty()).send(call) }
        }
        route.get("/api/sessions/{id}/transcript") {
            guarded(call) {
                val id = call.parameters["id"].orEmpty()
                val query = call.request.queryParameters
                routes.transcript(id, query["cursor"], query["limit"]?.toIntOrNull()).send(call)
            }
        }
        teamsRoutes?.let { teamRoutes(route, it) }
        projectsRoutes?.let { projects ->
            route.get("/api/projects") { guarded(call) { projects.list().send(call) } }
            route.get("/api/projects/{id}") { guarded(call) { projects.project(id(call)).send(call) } }
            route.get("/api/projects/{id}/files") { guarded(call) { projects.files(id(call)).send(call) } }
        }
    }

    /** V4-131 (FEATURES.md 6.1): the SPLIT team reads and the team writes. There is deliberately no
     *  GET /api/teams/{id}; the board composes from these and /api/sessions. */
    private fun teamRoutes(route: Route, teams: TeamsRoutes) {
        route.get("/api/teams") { guarded(call) { teams.list().send(call) } }
        route.put("/api/teams") {
            guarded(call) { teams.create(call.receiveText(), call.request.headers["Idempotency-Key"]).send(call) }
        }
        route.put("/api/teams/{id}") { guarded(call) { teams.replace(id(call), call.receiveText()).send(call) } }
        route.put("/api/teams/{id}/sessions") { guarded(call) { teams.bind(id(call), call.receiveText()).send(call) } }
        route.put("/api/teams/{id}/slots/{slot}/instructions") {
            guarded(call) { teams.instruct(id(call), call.parameters["slot"].orEmpty(), call.receiveText()).send(call) }
        }
        route.post("/api/teams/{id}/archive") { guarded(call) { teams.archive(id(call)).send(call) } }
        route.get("/api/teams/{id}/edges") { guarded(call) { teams.reads.edges(id(call)).send(call) } }
        route.get("/api/teams/{id}/chat") {
            guarded(call) { teams.reads.chat(id(call), call.request.queryParameters["day"]).send(call) }
        }
        route.get("/api/teams/{id}/activity") {
            guarded(call) { teams.reads.activity(id(call), call.request.queryParameters["day"]).send(call) }
        }
        route.get("/api/teams/{id}/economics") { guarded(call) { teams.economics(id(call)).send(call) } }
    }

    private fun id(call: ApplicationCall): String = call.parameters["id"].orEmpty()

    /** The console's routes, split out of [controlEngine] for the same reason that function was split
     *  out of start(): the table outgrew the 50-line wall a row at a time, and the wall was measuring
     *  the TABLE rather than any one job. A second level of the same split is not indirection for its
     *  own sake — each level has a reason to be read on its own.
     *
     *  A PLAIN FUNCTION TAKING THE ROUTE, never a `Route.` extension: kt-no-extension-functions is a
     *  standing wall, and it already corrected the two JsonArrayBuilder helpers next door.
     *
     *  Every route here is bearer-guarded, and every one reads its port AT CALL TIME through the
     *  routing lambda — [declaredHeads], [doctor], [upgrade] and [supervised] are all assigned after
     *  this server is constructed, so a route that captured one would answer against a null forever.
     *  ?head= is REQUIRED where the resource is per-head and a bad one is a 400 NAMING it, never a
     *  404, which the console reads as route-not-built; and every unwired port answers a named 5xx
     *  rather than a payload that reads as a confident negative. */
    private fun consoleRoutes(route: Route) {
        route.get("/api/perf/turns") { guarded(call) { perfRoutes.turns(call) } }
        route.get("/api/topology") { guarded(call) { topologyRoutes.read().send(call) } }
        route.put("/api/topology") { guarded(call) { topologyRoutes.write(call.receiveText()).send(call) } }
        route.get("/api/models") { guarded(call) { modelsRoute.models(call, ports.declaredHeads) } }
        route.get("/api/doctor") { guarded(call) { doctorRoute.doctorJson(call, ports.doctor) } }
        route.get("/api/upgrade") { guarded(call) { upgradeRoute.upgradeJson(call, ports.upgrade) } }
        // The same drain POST /api/daemon/shutdown requests, offered as a restart because the host
        // unit brings the daemon back. REFUSED when nothing would, and the refusal takes no drain.
        route.post("/api/daemon/restart") {
            guarded(call) { daemonRoutes.restartJson(call, shutdownDaemon, ports.supervised) }
        }
    }

    @Synchronized
    public fun stop() {
        mcpHost?.stop()
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }

    /** Reads [events] at CALL time, like [compaction]: ControlPlane assigns it after construction, so a
     *  route that captured the value would capture null forever. */
    private suspend fun streamEvents(call: ApplicationCall) {
        val bus = ports.events
        if (bus == null) {
            call.respondText(
                buildJsonObject { put("error", EVENTS_UNWIRED) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        EventsRoute(bus).stream(call)
    }

    private suspend fun guarded(call: ApplicationCall, mcp: Boolean = false, block: MgmtRoute) {
        val header = call.request.headers["Authorization"]
        val authorized = mgmtKey.matchesBearer(header) || (mcp && mcpAccessKey.matchesBearer(header))
        if (!authorized) {
            call.respondText(
                buildJsonObject { put("error", "unauthorized") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return
        }
        block()
    }

    private suspend fun respond(call: ApplicationCall, body: String) =
        call.respondText(body, ContentType.Application.Json)

    // The query-param tail clamp both /api/perf and /api/logs/{head} apply — hoisted out of
    // ControlPayloads.perfJson's and HeadRoutes.logsJson's original call sites.
    private fun tail(call: ApplicationCall, default: Int): Int =
        (call.request.queryParameters["tail"]?.toIntOrNull() ?: default).coerceIn(1, MAX_TAIL)
}
