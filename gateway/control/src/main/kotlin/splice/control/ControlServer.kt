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
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.api.AuthRoutes
import splice.control.api.CompactPayloads
import splice.control.api.ConfigRoutes
import splice.control.api.ControlAudit
import splice.control.api.ControlPayloads
import splice.control.api.EconomicsPayloads
import splice.control.api.EventBus
import splice.control.api.EventsRoute
import splice.control.api.HeadResolver
import splice.control.api.HeadRoutes
import splice.control.api.JsonBody
import splice.control.api.LaunchRoutes
import splice.control.api.McpRoutes
import splice.control.api.PerfPayloads
import splice.control.api.SessionsRoutes
import splice.control.api.StatuslineRoute
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
    /** v0.4.0 (V4-126, FEATURES.md §6): the console event bus. PUBLIC because the daemon publishes
     *  to it from the turn path in :app — this is the one type that crosses that boundary.
     *
     *  A BODY property, not a constructor parameter, and that is a wall decision rather than a style
     *  one: as a parameter it widened this constructor 17 -> 18, which the constructor-width ratchet
     *  reported as WIDENED on a file already recorded as debt ("a recorded offender is DEBT, not
     *  permission to keep adding parameters"). The bus is a derived collaborator with no
     *  construction-time input, so the body is also where it belongs — the same disposition HeadDeps
     *  gave its own derived collaborator. */
    public val events: EventBus = EventBus()
    private val mcpAccessKey = McpAccessKey(mgmtKey::get)
    private val eventsRoute = EventsRoute(events)
    private val sessionsRoutes = sessions?.let(::SessionsRoutes)
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
        val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
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
                if (sessionsRoutes != null) {
                    get("/api/sessions") { guarded(call) { respond(call, sessionsRoutes.sessionsJson()) } }
                }
                get("/api/logs/{head}") { guarded(call) { headRoutes.logsJson(call, tail(call, DEFAULT_LOG_TAIL)) } }
                // V4-126: additive. Every poll route above is untouched and stays the fallback.
                get("/api/events") { guarded(call) { eventsRoute.stream(call) } }
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
        engine.start(wait = false)
        server = engine
        mcpHost?.start()
    }

    @Synchronized
    public fun stop() {
        mcpHost?.stop()
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
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
