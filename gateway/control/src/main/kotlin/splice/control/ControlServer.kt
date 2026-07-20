// PORT-OF: server/src/control/api.mjs + control-server.mjs @ 4ca99f7 — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head + the committed
// single-file dashboard at /. Single-daemon simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state. JSON payload shapes match webui/src/shared/api/index.ts so the
// unmodified dashboard runs against this daemon (the P4-WEBUI contract).
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
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.perf.PerfKeys
import splice.core.usage.RateLimitState
import splice.core.usage.computeUsageWarn
import splice.core.util.runCatchingCancellable

// the two identifier literals every payload row repeats — named once for the whole file
private const val KEY = "key"
private const val LABEL = "label"

public class ControlServer(
    private val port: Int,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit,
    private val launchService: LaunchService? = null,
    private val shutdownDaemon: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val payloads = ControlPayloads(heads, config)

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
                get("/api/heads") { guarded(call) { respond(call, payloads.headsJson()) } }
                post("/api/heads/{head}/{action}") { guarded(call) { headAction(call) } }
                post("/api/daemon/shutdown") {
                    guarded(call) {
                        call.respondText(
                            buildJsonObject { put("ok", true) }.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Accepted,
                        )
                        shutdownDaemon()
                    }
                }
                get("/api/config") { guarded(call) { respond(call, payloads.configJson()) } }
                patch("/api/config") { guarded(call) { patchConfig(call) } }
                get("/api/usage") { guarded(call) { respond(call, payloads.usageJson()) } }
                get("/api/perf") {
                    guarded(call) {
                        val tail = (call.request.queryParameters["tail"]?.toIntOrNull() ?: DEFAULT_PERF_TAIL)
                            .coerceIn(1, MAX_TAIL)
                        respond(call, payloads.perfJson(tail))
                    }
                }
                get("/api/auth") { guarded(call) { respond(call, payloads.authJson()) } }
                post("/api/auth/{head}/{action}") { guarded(call) { authAction(call) } }
                get("/api/compact") { guarded(call) { respond(call, payloads.compactJson()) } }
                get("/api/logs/{head}") { guarded(call) { logsJson(call) } }
                post("/launch/{head}") { guarded(call) { launch(call) } }
                post("/statusline/{head}") { statusline(call) } // stdin-piped per tick; no bearer
                get("/statusline/{head}") { statusline(call) }
            }
        }
        engine.start(wait = false)
        server = engine
    }

    @Synchronized
    public fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }

    private suspend fun guarded(call: ApplicationCall, block: suspend () -> Unit) {
        if (!mgmtKey.matchesBearer(call.request.headers["Authorization"])) {
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

    private suspend fun headAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = heads[key]
        if (managed == null) {
            call.respondText(payloads.errorJson("unknown head"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        when (action) {
            "start" -> managed.head.start()
            "stop" -> managed.head.stop()
            "restart" -> managed.head.restart()
            else -> {
                call.respondText(
                    payloads.errorJson("unknown action"),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
        }
        log("[control] head $key -> $action\n")
        respond(call, payloads.headStatus(managed).toString())
    }

    private suspend fun patchConfig(call: ApplicationCall) {
        val partial = runCatchingCancellable { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (partial == null) {
            call.respondText(
                payloads.errorJson("invalid body"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        // JsonNull must map to Kotlin null (= DELETE) — `(v as? JsonPrimitive)?.content` turned
        // it into the 4-char string "null" and PERSISTED it (audit 2026-07-18). Objects/arrays
        // are rejected outright instead of being silently stringified or deleted.
        val nonScalar = partial.filterValues { it !is JsonPrimitive }.keys
        val map = (partial - nonScalar).mapValues { (_, v) ->
            (v as JsonPrimitive).takeUnless { it is JsonNull }?.content
        }
        // NO per-head fanout needed (single JVM) — but NOTE: most knobs are snapshotted at
        // Daemon.start (restart-required); only the genuinely hot ones apply to the next request.
        val result = config.patch(map)
        respond(
            call,
            buildJsonObject {
                put("applied", payloads.mapToJson(result.applied))
                putJsonObject("rejected") {
                    result.rejected.forEach { (k, v) -> put(k, v) }
                    nonScalar.forEach { put(it, "invalid value (must be a scalar or null)") }
                }
                putJsonArray("restart_required") { result.restartRequired.forEach { add(it) } }
                putJsonArray("targets") {} // no per-head fanout targets in single-daemon
                put("persisted", "state/config.json")
            }.toString(),
        )
    }

    private suspend fun authAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = heads[key]
        val refreshable = managed?.auth as? splice.core.auth.RefreshableAuthProvider
        if (action == "refresh" && refreshable != null) {
            // The dashboard's primary remediation control must not lie: a failed refresh
            // (null credentials back) reports ok:false so the operator re-logins instead of
            // staring at a green button while 401s continue (audit 2026-07-18).
            val refreshed = refreshable.refresh()
            respond(
                call,
                buildJsonObject {
                    put("ok", refreshed != null)
                    put("head", key)
                    if (refreshed == null) put("note", "refresh failed — check daemon.log; re-login likely required")
                }.toString(),
            )
        } else {
            // browser login lands with the launcher (P4-LAUNCH); ack for now
            respond(
                call,
                buildJsonObject {
                    put("ok", false)
                    put("note", "not supported in-process")
                }.toString(),
            )
        }
    }

    private suspend fun logsJson(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val tail = (call.request.queryParameters["tail"]?.toIntOrNull() ?: DEFAULT_LOG_TAIL).coerceIn(1, MAX_TAIL)
        val managed = heads[key]
        if (managed == null) {
            call.respondText(payloads.errorJson("unknown head"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        // PORT-OF server/src/control/api.mjs logs payload @ 4ca99f7: {key, path, lines:[...]}
        // (webui LogsPayload) — lines is an ARRAY (tail split), not one blob.
        val lines = managed.logs.tail(tail).split("\n").filter { it.isNotEmpty() }
        respond(
            call,
            buildJsonObject {
                put(KEY, key)
                put("path", managed.logs.path())
                putJsonArray("lines") { lines.forEach { add(it) } }
            }.toString(),
        )
    }

    private suspend fun launch(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = heads[key]
        val spec = managed?.launchSpec
        if (spec == null || launchService == null) {
            call.respondText(
                payloads.errorJson("head not launchable"),
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return
        }
        val body = runCatchingCancellable { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        // Safe by default: the caller must explicitly opt in with {"dangerouslySkipPermissions":"true"}
        // to get the flag; a missing key, malformed body, or any other value stays safe.
        val dangerouslySkipPermissions = body?.get("dangerouslySkipPermissions")?.jsonPrimitive?.content == "true"
        val extraArgs = (body?.get("args") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val recipe = launchService.launch(spec, extraArgs, dangerouslySkipPermissions)
        log("[control] launch $key -> ${recipe.argv}\n")
        if (recipe.warning != null) log("[control] ${recipe.warning}\n")
        respond(
            call,
            buildJsonObject {
                putJsonObject("env") { recipe.env.forEach { (k, v) -> put(k, v) } }
                putJsonArray("unset") { recipe.unset.forEach { add(it) } }
                putJsonArray("argv") { recipe.argv.forEach { add(it) } }
                if (recipe.warning != null) put("warning", recipe.warning)
            }.toString(),
        )
    }

    private suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = heads[key]
        if (managed == null) {
            call.respondText(managed?.head?.label ?: key, ContentType.Text.Plain)
            return
        }
        val stdin = runCatchingCancellable { call.receiveText() }.getOrDefault("")
        val line = StatuslineRenderer(managed.head.label)
            .render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h)
        call.respondText(line, ContentType.Text.Plain)
    }

    private companion object {
        const val STOP_GRACE_MS = 100L
        const val STOP_TIMEOUT_MS = 500L
        const val DEFAULT_LOG_TAIL = 200
        const val DEFAULT_PERF_TAIL = 200
        const val MAX_TAIL = 2_000
    }
}

// PORT-OF server/src/control/api.mjs payload shapes @ 4ca99f7 — the read-only JSON builders for the
// control API, split out of ControlServer so the server class stays focused on routing/lifecycle.
// The P4-WEBUI wire field names (the dashboard contract) live here.
private class ControlPayloads(
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
) {
    fun controlHealthJson(): String = buildJsonObject {
        put("ok", true)
        put("version", GATEWAY_VERSION)
        put("wantShimVersion", SHIM_VERSION)
        put(HEADS, heads.size)
    }.toString()

    fun statusJson(): String = buildJsonObject {
        put("server", "control")
        put("version", GATEWAY_VERSION)
        putJsonArray(HEADS) { heads.keys.forEach { add(it) } }
        putJsonArray("registry") {
            heads.values.forEach { m ->
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    put("authKind", m.auth.let { "provider" })
                }
            }
        }
    }.toString()

    fun headsJson(): String = buildJsonObject {
        putJsonArray(HEADS) { heads.values.forEach { add(headStatus(it)) } }
    }.toString()

    // PORT-OF server/launcher/heads.mjs status shape @ 4ca99f7. In the single daemon the head is
    // in-process, so healthy/version are authoritative and versionMatch is always true when up.
    fun headStatus(m: ManagedHead) = buildJsonObject {
        val h = m.head.healthSnapshot()
        put(KEY, m.head.key)
        put(LABEL, m.head.label)
        put("name", m.head.key)
        put("port", m.head.port)
        put("authKind", m.authKind)
        put("wantVersion", GATEWAY_VERSION)
        put("running", h.running)
        put("healthy", h.ok)
        put("version", if (h.running) GATEWAY_VERSION else null as String?)
        put("versionMatch", if (h.running) true else null as Boolean?)
        put("mode", null as String?)
        put("gate", null as String?) // gate snapshot is nullable in the contract; heads self-report when wired
        put("maxInflight", null as Int?)
        // G20: passive per-head health counters, local-origin vs provider-error split — diagnosis
        // only, surfaced through this aggregation (never the per-head /health liveness route).
        putJsonObject("health") {
            put("localOriginErrors", h.localOriginErrors)
            put("providerErrors", h.providerErrors)
        }
        putJsonArray("pids") {}
    }

    fun configJson(): String {
        val layers = config.layers()
        val effective = config.getConfig().asMap()
        return buildJsonObject {
            put("effective", mapToJson(effective))
            putJsonObject("layers") {
                put("defaults", mapToJson(layers.defaults))
                // The operator-facing layer: ~/.config/splice/splice.toml [daemon]/[defaults].
                // Shown in precedence position (beats defaults, loses to file/env/runtime) so
                // "why is this knob X?" is answerable from the payload alone.
                put("toml", mapToJson(layers.headOverrides))
                put("file", mapToJson(layers.file))
                put("env", mapToJson(layers.env))
                put("runtime", mapToJson(layers.runtime))
            }
            putJsonArray("restart_required_keys") { Knob.restartRequiredKeys.forEach { add(it) } }
            put("source", "control")
        }.toString()
    }

    // PORT-OF server/src/control/api.mjs usage payload @ 4ca99f7: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray(HEADS) {
                heads.values.forEach { m ->
                    val rlView = m.usage.ratelimit()
                    val rl = rlView?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
                    val warn = computeUsageWarn(m.usage.outputTokens5h(), rl, m.warnPct, m.warnTokens5h)
                    addJsonObject {
                        put(KEY, m.head.key)
                        put(LABEL, m.head.label)
                        putJsonObject("usage") {
                            put("output_tokens_5h", m.usage.outputTokens5h())
                            put("entries", m.usage.entries())
                            if (rlView != null) {
                                putJsonObject("ratelimit") {
                                    put("limit_tokens", rlView.limitTokens)
                                    put("remaining_tokens", rlView.remainingTokens)
                                    put("reset_tokens", rlView.resetTokens)
                                }
                            } else {
                                put("ratelimit", null as String?)
                            }
                            putJsonObject("warn") {
                                put("level", warn.level)
                                put("pct", warn.pct)
                                put("source", warn.source)
                                put("reset", warn.reset)
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    // PORT-OF server/src/control/api.mjs auth payload @ 4ca99f7: keyed by head (Node hardcodes
    // `codex`; multi-head keys each), value = {kind, login, present, ...describe fields}. The webui
    // AuthPayload reads `.codex`. login = automated for oauth, manual for api-key.
    suspend fun authJson(): String {
        val described = heads.values.map { m -> m to m.auth.describe() }
        return buildJsonObject {
            described.forEach { (m, desc) ->
                putJsonObject(m.head.key) {
                    put("kind", desc.kind)
                    put("login", if (desc.kind.contains("oauth")) "automated" else "manual")
                    put("present", desc.present)
                    desc.fields.forEach { (k, v) -> put(k, v) }
                }
            }
        }.toString()
    }

    // PORT-OF server/src/control/api.mjs compact payload @ 4ca99f7: a FLAT {stats:[...]} of the
    // recent compaction rows (webui CompactPayload). Node reads codex's file; multi-head flattens
    // every head's tail (each row tagged with its head key) newest-last.
    fun compactJson(): String = buildJsonObject {
        putJsonArray("stats") {
            heads.values.forEach { m ->
                val s = m.compact.summary(COMPACT_TAIL)
                s.tail.forEach { row ->
                    addJsonObject {
                        put("head", m.head.key)
                        row.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
        }
    }.toString()

    // NEW (bottleneck instrument): per-head stage aggregation over the recent perf rows.
    // {heads:[{key,label,count,stages:{<field>:{count,p50,p95,max}}}]} — fields are the TurnPerf
    // marks/counters (PerfKeys names), marks first in pipeline order, counters after.
    fun perfJson(tailN: Int): String = buildJsonObject {
        put("window", tailN)
        putJsonArray(HEADS) {
            heads.values.forEach { m ->
                val rows = m.perf?.tailNumeric(tailN).orEmpty()
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    put("count", rows.size)
                    putJsonObject("stages") {
                        orderedPerfFields(rows).forEach { field ->
                            val values = rows.mapNotNull { it[field] }
                            if (values.isNotEmpty()) put(field, statsJson(values))
                        }
                    }
                }
            }
        }
    }.toString()

    /** PerfKeys.markOrder first (pipeline order), then every other seen field alphabetically. */
    private fun orderedPerfFields(rows: List<Map<String, Long>>): List<String> {
        val seen = rows.flatMapTo(LinkedHashSet()) { it.keys } - "ts"
        val marks = PerfKeys.markOrder.filter { it in seen }
        val rest = (seen - PerfKeys.markOrder.toSet()).sorted()
        return marks + rest
    }

    private fun statsJson(values: List<Long>): JsonObject {
        val sorted = values.sorted()
        return buildJsonObject {
            put("count", sorted.size)
            put("p50", percentile(sorted, P50))
            put("p95", percentile(sorted, P95))
            put("max", sorted.last())
        }
    }

    /** Nearest-rank percentile on a pre-sorted list. */
    private fun percentile(sorted: List<Long>, q: Double): Long {
        val rank = kotlin.math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    fun mapToJson(m: Map<String, Any?>) = buildJsonObject {
        m.forEach { (k, v) ->
            when (v) {
                null -> put(k, null as String?)
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    private companion object {
        const val HEADS = "heads"
        const val COMPACT_TAIL = 50
        const val USAGE_WINDOW_HOURS = 5
        const val P50 = 0.50
        const val P95 = 0.95
    }
}
