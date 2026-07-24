// PORT-OF: server/src/control/api.mjs + control-server.mjs @ pre-public-port-baseline — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head + the committed
// single-file dashboard at /. Single-daemon simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state. JSON payload shapes match webui/src/shared/api/index.ts so the
// unmodified dashboard runs against this daemon (the P4-WEBUI contract).
package splice.control

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
import splice.core.auth.AuthDescription
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.perf.PerfKeys
import splice.core.topology.ambiguousHeadMessage
import splice.core.usage.RateLimitState
import splice.core.usage.computeUsageWarn
import splice.core.util.runCatchingCancellable
import java.io.ByteArrayOutputStream

// the two identifier literals every payload row repeats — named once for the whole file
private const val KEY = "key"
private const val LABEL = "label"

private data class LaunchRequest(val extraArgs: List<String>, val dangerouslySkipPermissions: Boolean)

private fun mapToJson(values: Map<String, Any?>) = buildJsonObject {
    values.forEach { (key, value) ->
        when (value) {
            null -> put(key, null as String?)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}

public class ControlServer(
    private val port: Int,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit,
    private val launchService: LaunchService? = null,
    private val shutdownDaemon: () -> Unit = {},
    // Live count of heads that failed to assemble or start (Daemon.start's `failed` map) — lets
    // the /health readyHeads protocol converge on a degraded boot instead of waiting forever for
    // a head that will never become ready (review 2026-07-22 round 3).
    private val failedHeads: () -> Int = { 0 },
    // Total CONFIGURED heads (topology). The readyHeads + failedHeads == heads invariant only holds
    // against the configured total: an assembly-failed head is counted in failedHeads but is NEVER
    // in the `heads` map, so reporting heads.size broke the invariant for it (review 2026-07-23).
    private val configuredHeads: Int = heads.size,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val payloads = ControlPayloads(heads, config, failedHeads, configuredHeads)

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
        val managed = resolveHeadOrRespond(call, heads, payloads, key) ?: return
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
                put("applied", mapToJson(result.applied))
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
        val managed = resolveHeadOrRespond(call, heads, payloads, key) ?: return
        val refreshable = managed.auth as? splice.core.auth.RefreshableAuthProvider
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
        val managed = resolveHeadOrRespond(call, heads, payloads, key) ?: return
        // PORT-OF server/src/control/api.mjs logs payload @ pre-public-port-baseline: {key, path, lines:[...]}
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
        val targets = launchTargets(heads, key)
        if (targets.size > 1) {
            call.respondText(
                payloads.errorJson(ambiguousHeadMessage(key, targets.map { it.head.key })),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        val managed = targets.firstOrNull()
        val spec = managed?.launchSpec
        if (spec == null || launchService == null) {
            val known = heads.values.joinToString(", ") { it.head.label }
            call.respondText(
                payloads.errorJson("no launchable head named '$key' (configured: $known)"),
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return
        }
        if (!managed.head.healthSnapshot().running) {
            call.respondText(
                payloads.errorJson("head is not running"),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val request = receiveLaunchRequest(call)
        val recipe = withAuthWarning(
            managed,
            spec,
            launchService.launch(spec, request.extraArgs, request.dangerouslySkipPermissions),
        )
        log("[control] launch $key -> ${recipe.argv}\n")
        if (recipe.warning != null) log("[control] ${recipe.warning}\n")
        respond(call, launchRecipeJson(recipe))
    }

    private suspend fun receiveLaunchRequest(call: ApplicationCall): LaunchRequest {
        val body = runCatchingCancellable { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        // Safe by default: the caller must explicitly opt in with {"dangerouslySkipPermissions":"true"}
        // to get the flag; a missing key, malformed body, or any other value stays safe.
        val dangerouslySkipPermissions = body?.get("dangerouslySkipPermissions")?.jsonPrimitive?.content == "true"
        val extraArgs = (body?.get("args") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return LaunchRequest(extraArgs, dangerouslySkipPermissions)
    }

    private suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = headByName(heads, key).singleOrNull()
        if (managed == null) {
            call.respondText(managed?.head?.label ?: key, ContentType.Text.Plain)
            return
        }
        val stdin = try {
            receiveStatuslineBody(call)
        } catch (_: StatuslineBodyTooLarge) {
            call.respondText(
                "statusline body exceeds $MAX_STATUSLINE_BYTES bytes",
                ContentType.Text.Plain,
                HttpStatusCode(CONTENT_TOO_LARGE_STATUS, "Content Too Large"),
            )
            return
        } catch (_: TimeoutCancellationException) {
            call.respondText("statusline body timed out", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
            return
        }
        val line = StatuslineRenderer(managed.head.label, config.getConfig().statuslineGitRoots)
            .render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h)
        call.respondText(line, ContentType.Text.Plain)
    }

    private suspend fun receiveStatuslineBody(call: ApplicationCall): String =
        withTimeout(STATUSLINE_READ_TIMEOUT_MS) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_STATUSLINE_BYTES) throw StatuslineBodyTooLarge()
            val channel = call.receiveChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STATUSLINE_READ_BUFFER_BYTES)
            var total = 0
            var read = readAvailableOrEof(channel, buffer)
            while (read >= 0) {
                total += read
                if (total > MAX_STATUSLINE_BYTES) throw StatuslineBodyTooLarge()
                output.write(buffer, 0, read)
                read = readAvailableOrEof(channel, buffer)
            }
            output.toString(Charsets.UTF_8)
        }

    private suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Int {
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read == 0) {
            if (!channel.awaitContent(1)) return -1
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return read
    }

    private companion object {
        const val STOP_GRACE_MS = 100L
        const val STOP_TIMEOUT_MS = 500L
        const val DEFAULT_LOG_TAIL = 200
        const val DEFAULT_PERF_TAIL = 200
        const val MAX_TAIL = 2_000
        const val MAX_STATUSLINE_BYTES = 64 * 1024
        const val STATUSLINE_READ_BUFFER_BYTES = 8 * 1024
        const val STATUSLINE_READ_TIMEOUT_MS = 2_000L
        const val CONTENT_TOO_LARGE_STATUS = 413
    }
}

private class StatuslineBodyTooLarge : RuntimeException()

// PORT-OF server/src/control/api.mjs payload shapes @ pre-public-port-baseline — the read-only JSON builders for the
// control API, split out of ControlServer so the server class stays focused on routing/lifecycle.
// The P4-WEBUI wire field names (the dashboard contract) live here.
private class ControlPayloads(
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val failedHeads: () -> Int,
    private val configuredHeads: Int,
) {
    fun controlHealthJson(): String = buildJsonObject {
        put("ok", true)
        put("version", GATEWAY_VERSION)
        put("wantShimVersion", SHIM_VERSION)
        // Configured total, NOT heads.size (assembled only) — see the ControlServer ctor comment.
        put(HEADS, configuredHeads)
        // Launch shims wait for readyHeads + failedHeads == heads before POSTing /launch (post
        // startDaemonHeads) — NOT readyHeads == heads: a start-failed head stays in `heads`
        // forever with running=false, so the old equality-wait spun forever on a degraded boot
        // (review 2026-07-22 round 3).
        val ready = heads.values.count { it.head.healthSnapshot().running }
        put("readyHeads", ready)
        put("failedHeads", failedHeads())
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
                    put("authKind", m.authKind)
                }
            }
        }
    }.toString()

    fun headsJson(): String = buildJsonObject {
        putJsonArray(HEADS) { heads.values.forEach { add(headStatus(it)) } }
    }.toString()

    // PORT-OF server/launcher/heads.mjs status shape @ pre-public-port-baseline. In the single daemon the head is
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
        // Live InflightGate snapshot for the dashboard (was permanently null after the Kotlin port).
        putJsonObject("gate") {
            put("inflight", h.gateInflight)
            put("queued", h.gateQueued)
            if (h.gateLimit <= 0) put("max", "unlimited") else put("max", h.gateLimit)
            // Counters the Node gate tracked; Kotlin gate has no acquired/released totals —
            // zero-fill so the GateSnapshot shape stays stable for the webui.
            put("acquired", 0)
            put("released", 0)
            put("waited", 0)
            put("avg_wait_ms", 0)
            putJsonArray("live") {}
            put("stream_idle_ms", 0)
        }
        put("maxInflight", if (h.gateLimit <= 0) null else h.gateLimit)
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

    // PORT-OF server/src/control/api.mjs usage payload @ pre-public-port-baseline: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray(HEADS) {
                heads.values.forEach { m ->
                    val usage = m.usage.snapshot()
                    val rlView = usage.ratelimit
                    val rl = rlView?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
                    val warn = computeUsageWarn(usage.outputTokens5h, rl, m.warnPct, m.warnTokens5h)
                    addJsonObject {
                        put(KEY, m.head.key)
                        put(LABEL, m.head.label)
                        putJsonObject("usage") {
                            put("output_tokens_5h", usage.outputTokens5h)
                            put("entries", usage.entries)
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

    // PORT-OF server/src/control/api.mjs auth payload @ pre-public-port-baseline: keyed by head (Node hardcodes
    // `codex`; multi-head keys each), value = {kind, login, present, ...describe fields}. The webui
    // AuthPayload reads every configured head. login = automated for oauth, manual for api-key.
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

    // Aggregate every head while retaining a head tag on each tail row. This is the dashboard's
    // actual CompactPayload contract: totals/outcomes plus a bounded newest-last event tail.
    fun compactJson(): String {
        val summaries = heads.values.map { it to it.compact.summary(COMPACT_TAIL) }
        val outcomes = LinkedHashMap<String, Int>()
        summaries.forEach { (_, summary) ->
            summary.byOutcome.forEach { (outcome, count) ->
                outcomes[outcome] = outcomes.getOrDefault(outcome, 0) + count
            }
        }
        val tail = summaries.flatMap { (managed, summary) ->
            summary.tail.map { row -> managed.head.key to row }
        }.sortedBy { (_, row) -> row["ts"]?.toLongOrNull() ?: 0L }
            .takeLast(COMPACT_TAIL)
        return buildJsonObject {
            putJsonObject("stats") {
                put("total", summaries.sumOf { (_, summary) -> summary.total })
                putJsonObject("by_outcome") {
                    outcomes.forEach { (outcome, count) -> put(outcome, count) }
                }
                putJsonArray("tail") {
                    tail.forEach { (head, row) ->
                        addJsonObject {
                            put("head", head)
                            row.forEach { (key, value) -> putCompactScalar(key, value) }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCompactScalar(key: String, value: String) {
        val numeric = if (key in COMPACT_NUMERIC_FIELDS) value.toLongOrNull() else null
        if (numeric == null) put(key, value) else put(key, numeric)
    }

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

    private companion object {
        const val HEADS = "heads"
        const val COMPACT_TAIL = 50
        const val USAGE_WINDOW_HOURS = 5
        const val P50 = 0.50
        const val P95 = 0.95
        val COMPACT_NUMERIC_FIELDS = setOf("ts", "chars", "ms", "status")
    }
}

// The shim names a head by its wrapper command (argv[0]); the topology keys heads independently
// (starter: head `openrouter`, command `claudeor`). Accept either name — a map-KEY match (unique)
// comes first for precedence, then every LABEL (wrapper command) match. Two label matches mean a
// misconfigured topology sharing one command; callers decide unknown-vs-ambiguous from the size.
private fun headByName(heads: Map<String, ManagedHead>, name: String): List<ManagedHead> {
    val byKey = heads[name]
    val byLabel = heads.values.filter { it.head.label == name && it !== byKey }
    return listOfNotNull(byKey) + byLabel
}

// One head for a by-name /api route, or null after answering the error itself: an exact KEY match
// wins outright (the dashboard always sends keys, and a key must never be shadowed by another
// head's colliding command); otherwise wrapper-command matches — none is a 404, and 2+ is a 409
// naming the colliding heads so a shared-command misconfiguration never reads as a typo.
private suspend fun resolveHeadOrRespond(
    call: ApplicationCall,
    heads: Map<String, ManagedHead>,
    payloads: ControlPayloads,
    name: String,
): ManagedHead? {
    heads[name]?.let { return it }
    val byLabel = heads.values.filter { it.head.label == name }
    return when {
        byLabel.size > 1 -> {
            call.respondText(
                payloads.errorJson(ambiguousHeadMessage(name, byLabel.map { it.head.key })),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            null
        }
        byLabel.isEmpty() -> {
            call.respondText(payloads.errorJson("unknown head"), ContentType.Application.Json, HttpStatusCode.NotFound)
            null
        }
        else -> byLabel.single()
    }
}

// The launchable heads a `/launch/<name>` resolves to, with precedence applied so the launchable
// filter runs across BOTH key- and label-matched candidates: a launchable KEY match wins outright
// (fixes the latent case where a bare key match with no launchSpec shadowed a launchable command);
// otherwise every launchable LABEL match — 0 = unknown, 1 = ready, 2+ = ambiguous (shared command).
private fun launchTargets(heads: Map<String, ManagedHead>, name: String): List<ManagedHead> {
    heads[name]?.takeIf { it.launchSpec != null }?.let { return listOf(it) }
    return headByName(heads, name).filter { it.launchSpec != null }
}

// The exec-recipe response body: {env, unset, argv, warning?} — the shim reads it to run the head.
private fun launchRecipeJson(recipe: LaunchRecipe): String = buildJsonObject {
    putJsonObject("env") { recipe.env.forEach { (k, v) -> put(k, v) } }
    putJsonArray("unset") { recipe.unset.forEach { add(it) } }
    putJsonArray("argv") { recipe.argv.forEach { add(it) } }
    if (recipe.warning != null) put("warning", recipe.warning)
}.toString()

// A head with no upstream credentials still launches (Claude Code opens fine) but every
// request 401s upstream — warn NOW, at the moment the user can still fix it.
private suspend fun withAuthWarning(managed: ManagedHead, spec: LaunchSpec, raw: LaunchRecipe): LaunchRecipe {
    val auth = managed.auth.describe()
    return if (auth.present) {
        raw
    } else {
        raw.copy(warning = listOfNotNull(raw.warning, missingAuthWarning(managed, auth, spec)).joinToString("; "))
    }
}

// Names the exact fix: the env var for an api-key head, the login command for an OAuth head.
// The key is read from the DAEMON's environment, so "export then retry" silently fails until
// the daemon restarts — the message says so.
private fun missingAuthWarning(managed: ManagedHead, auth: AuthDescription, spec: LaunchSpec): String {
    val label = managed.head.label
    val envVar = auth.fields["env_var"]
    val keyFile = auth.fields["key_file"]
    return when {
        // A file-configured head's primary fix is the file it reads, not an env var it never used.
        keyFile != null ->
            "'$label' has no upstream API key: add it to $keyFile " +
                "(or export $envVar) — then run: splice restart"
        envVar != null ->
            "'$label' has no upstream API key: $envVar is not set in the daemon's environment. " +
                "Requests will fail until you export $envVar and run: splice restart"
        else -> "'$label' is not signed in — requests will fail until you run: ${spec.loginCommand}"
    }
}
