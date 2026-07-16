// PORT-OF: server/src/control/api.mjs + control-server.mjs @ 4ca99f7 — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head + the committed
// single-file dashboard at /. Single-daemon simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state. JSON payload shapes match webui/src/shared/api/index.ts so the
// unmodified dashboard runs against this daemon (the P4-WEBUI contract).
@file:Suppress("StringLiteralDuplication", "TooManyFunctions") // route handlers + wire field names

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
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.usage.RateLimitState
import splice.core.usage.computeUsageWarn

public class ControlServer(
    private val port: Int,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: () -> String,
    private val log: (String) -> Unit,
    private val launchService: LaunchService? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    public fun start() {
        mgmtKey.get() // mint eagerly BEFORE the port opens — a dashboard load must not race it
        val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                get("/") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
                get("/dashboard") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
                get("/api/status") { guarded(call) { respond(call, statusJson()) } }
                get("/api/heads") { guarded(call) { respond(call, headsJson()) } }
                post("/api/heads/{head}/{action}") { guarded(call) { headAction(call) } }
                get("/api/config") { guarded(call) { respond(call, configJson()) } }
                patch("/api/config") { guarded(call) { patchConfig(call) } }
                get("/api/usage") { guarded(call) { respond(call, usageJson()) } }
                get("/api/auth") { guarded(call) { respond(call, authJson()) } }
                post("/api/auth/{head}/{action}") { guarded(call) { authAction(call) } }
                get("/api/compact") { guarded(call) { respond(call, compactJson()) } }
                get("/api/logs/{head}") { guarded(call) { logsJson(call) } }
                post("/launch/{head}") { guarded(call) { launch(call) } }
                post("/statusline/{head}") { statusline(call) } // stdin-piped per tick; no bearer
                get("/statusline/{head}") { statusline(call) }
            }
        }
        engine.start(wait = false)
        server = engine
    }

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

    private fun statusJson(): String = buildJsonObject {
        put("server", "control")
        put("version", GATEWAY_VERSION)
        putJsonArray("heads") { heads.keys.forEach { add(it) } }
        putJsonArray("registry") {
            heads.values.forEach { m ->
                addJsonObject {
                    put("key", m.head.key)
                    put("label", m.head.label)
                    put("authKind", m.auth.let { "provider" })
                }
            }
        }
    }.toString()

    private fun headsJson(): String = buildJsonObject {
        putJsonArray("heads") { heads.values.forEach { add(headStatus(it)) } }
    }.toString()

    // PORT-OF server/launcher/heads.mjs status shape @ 4ca99f7. In the single daemon the head is
    // in-process, so healthy/version are authoritative and versionMatch is always true when up.
    private fun headStatus(m: ManagedHead) = buildJsonObject {
        val h = m.head.healthSnapshot()
        put("key", m.head.key)
        put("label", m.head.label)
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
        putJsonArray("pids") {}
    }

    private suspend fun headAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = heads[key]
        if (managed == null) {
            call.respondText(errorJson("unknown head"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        when (action) {
            "start" -> managed.head.start()
            "stop" -> managed.head.stop()
            "restart" -> {
                managed.head.stop()
                managed.head.start()
            }
            else -> {
                call.respondText(errorJson("unknown action"), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return
            }
        }
        log("[control] head $key -> $action\n")
        respond(call, headStatus(managed).toString())
    }

    private fun configJson(): String {
        val layers = config.layers()
        val effective = config.getConfig().asMap()
        return buildJsonObject {
            put("effective", mapToJson(effective))
            putJsonObject("layers") {
                put("defaults", mapToJson(layers.defaults))
                put("file", mapToJson(layers.file))
                put("env", mapToJson(layers.env))
                put("runtime", mapToJson(layers.runtime))
            }
            putJsonArray("restart_required_keys") { Knob.restartRequiredKeys.forEach { add(it) } }
            put("source", "control")
        }.toString()
    }

    private suspend fun patchConfig(call: ApplicationCall) {
        val partial = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (partial == null) {
            call.respondText(errorJson("invalid body"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val map = partial.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content
        }
        // NO fanout: one in-process patch (single JVM). Heads read getConfig() fresh per request.
        val result = config.patch(map)
        respond(
            call,
            buildJsonObject {
                put("applied", mapToJson(result.applied))
                putJsonObject("rejected") { result.rejected.forEach { (k, v) -> put(k, v) } }
                putJsonArray("restart_required") { result.restartRequired.forEach { add(it) } }
                putJsonArray("targets") {} // no per-head fanout targets in single-daemon
                put("persisted", "state/config.json")
            }.toString(),
        )
    }

    // PORT-OF server/src/control/api.mjs usage payload @ 4ca99f7: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    private fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray("heads") {
                heads.values.forEach { m ->
                    val rlView = m.usage.ratelimit()
                    val rl = rlView?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
                    val warn = computeUsageWarn(m.usage.outputTokens5h(), rl, m.warnPct, m.warnTokens5h)
                    addJsonObject {
                        put("key", m.head.key)
                        put("label", m.head.label)
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
    private suspend fun authJson(): String {
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

    private suspend fun authAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = heads[key]
        val refreshable = managed?.auth as? splice.core.auth.RefreshableAuthProvider
        if (action == "refresh" && refreshable != null) {
            refreshable.refresh()
            respond(
                call,
                buildJsonObject {
                    put("ok", true)
                    put("head", key)
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

    // PORT-OF server/src/control/api.mjs compact payload @ 4ca99f7: a FLAT {stats:[...]} of the
    // recent compaction rows (webui CompactPayload). Node reads codex's file; multi-head flattens
    // every head's tail (each row tagged with its head key) newest-last.
    private fun compactJson(): String = buildJsonObject {
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

    private suspend fun logsJson(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: DEFAULT_LOG_TAIL
        val managed = heads[key]
        if (managed == null) {
            call.respondText(errorJson("unknown head"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        // PORT-OF server/src/control/api.mjs logs payload @ 4ca99f7: {key, path, lines:[...]}
        // (webui LogsPayload) — lines is an ARRAY (tail split), not one blob.
        val lines = managed.logs.tail(tail).split("\n").filter { it.isNotEmpty() }
        respond(
            call,
            buildJsonObject {
                put("key", key)
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
            call.respondText(errorJson("head not launchable"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val safe = body?.get("safe")?.jsonPrimitive?.content == "true"
        val extraArgs = (body?.get("args") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val recipe = launchService.launch(spec, extraArgs, safe)
        log("[control] launch $key -> ${recipe.argv}\n")
        respond(
            call,
            buildJsonObject {
                putJsonObject("env") { recipe.env.forEach { (k, v) -> put(k, v) } }
                putJsonArray("unset") { recipe.unset.forEach { add(it) } }
                putJsonArray("argv") { recipe.argv.forEach { add(it) } }
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
        val stdin = runCatching { call.receiveText() }.getOrDefault("")
        val line = StatuslineRenderer(managed.head.label)
            .render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h)
        call.respondText(line, ContentType.Text.Plain)
    }

    private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    private fun mapToJson(m: Map<String, Any?>) = buildJsonObject {
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
        const val STOP_GRACE_MS = 100L
        const val STOP_TIMEOUT_MS = 500L
        const val COMPACT_TAIL = 50
        const val DEFAULT_LOG_TAIL = 200
        const val USAGE_WINDOW_HOURS = 5
    }
}
