// NEW (v0.4.0, FEATURES.md §6): `splice doctor --json [--with-logs] [--out FILE]` — the shareable
// bug report. Schema version 1. Emission is an ALLOWLIST: every key below is named here, so a new
// topology or perf field is absent until someone adds it; account ids, e-mails, tokens and the
// session cwd have no key to ride. Free text (check details, log lines) goes through
// DoctorRedaction. The perf tail is the last 200 rows per head restricted to the numeric perf
// keys plus model and outcome. Nothing is uploaded: the report is printed, or written to --out,
// and with --with-logs the log lines are shown on the terminal before the file is written.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.LogFileSource
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.perf.PerfKeys
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private const val SCHEMA_VERSION = 1
private const val PERF_TAIL_ROWS = 200
private const val PERF_TAIL_BYTES = 256 * 1024
private const val LOG_TAIL_LINES = 500
private const val CONTEXT_WINDOW = "context_window"
private const val MODELS = "models"
private const val VERSION = "version"
private val PERF_FIELDS: Set<String> = setOf(
    "ts",
    "model",
    "outcome",
    "compact",
    PerfKeys.RECV,
    PerfKeys.PARSE,
    PerfKeys.BUILD,
    PerfKeys.GATE,
    PerfKeys.HEADERS,
    PerfKeys.FIRST_BYTE,
    PerfKeys.FIRST_FRAME,
    PerfKeys.FIRST_DELTA,
    PerfKeys.STREAM_END,
    PerfKeys.FINISH,
    PerfKeys.TOTAL,
    PerfKeys.AUTH_MS,
    PerfKeys.BACKOFF_MS,
    PerfKeys.REFRESH_MS,
    PerfKeys.WRITE_MS,
    PerfKeys.USAGE_MS,
    PerfKeys.ATTEMPTS,
    PerfKeys.RETRIES,
    PerfKeys.REFRESHES,
    PerfKeys.REQ_BYTES,
    PerfKeys.UPSTREAM_REQ_BYTES,
    PerfKeys.SSE_BYTES_IN,
    PerfKeys.EVENTS_IN,
    PerfKeys.FRAMES_OUT,
    PerfKeys.CONTENT_FRAMES_OUT,
    PerfKeys.FRAMES_SKIPPED,
    PerfKeys.BYTES_OUT,
    PerfKeys.OUT_TOKENS,
    PerfKeys.IN_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.INFLIGHT,
    PerfKeys.ASYNC_IO_DROPS,
    PerfKeys.TOOLS_EAGER,
    PerfKeys.TOOLS_DEFERRED,
    PerfKeys.SEARCH_ROUNDS,
    PerfKeys.POST_SEND_RETRIES,
)

/** What the operator asked for on the command line. */
internal data class DoctorReportOptions(
    val json: Boolean,
    val withLogs: Boolean,
    val out: Path?,
    /** v0.4.0 (FEATURES.md §10): send each local runtime one tiny streamed request with one tool. */
    val live: Boolean = false,
) {
    internal fun parse(args: List<String>): DoctorReportOptions {
        val outIndex = args.indexOf("--out")
        return DoctorReportOptions(
            json = "--json" in args,
            withLogs = "--with-logs" in args,
            out = args.getOrNull(outIndex + 1)?.takeIf { outIndex >= 0 }?.let(Paths::get),
            live = "--live" in args,
        )
    }
}

/** The collected doctor run: what the sections found, and the topology they read. */
internal data class DoctorRun(val topology: Topology?, val sections: List<Pair<String, List<DoctorCheck>>>)

internal class DoctorReport(
    envReader: EnvReader = EnvReader(System::getenv),
    private val claudeVersion: () -> String,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val statePaths: StatePaths = StatePaths(envReader = envReader),
    private val redaction: DoctorRedaction = DoctorRedaction(home),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Print or write the report; true when no check FAILED (the same verdict as the text doctor). */
    internal fun emit(run: DoctorRun, options: DoctorReportOptions): Boolean {
        val report = build(run, options.withLogs)
        val text = json.encodeToString(JsonObject.serializer(), report)
        val out = options.out
        if (out == null) {
            println(text)
        } else {
            if (options.withLogs) {
                println("daemon log lines leaving the machine in $out (after redaction):")
                (report["logs"] as? JsonArray)?.forEach { println("  " + (it as JsonPrimitive).content) }
            }
            Files.writeString(out, text + "\n")
            println("doctor report written to $out")
        }
        return run.sections.flatMap { it.second }.none { it.status == CheckStatus.FAIL }
    }

    internal fun build(run: DoctorRun, withLogs: Boolean): JsonObject = buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("generated_at", Instant.now().toString())
        putJsonObject("splice") { put(VERSION, GATEWAY_VERSION) }
        putJsonObject("claude_code") { put(VERSION, redaction.text(claudeVersion())) }
        putJsonObject("os") {
            put("name", System.getProperty("os.name"))
            put(VERSION, System.getProperty("os.version"))
            put("arch", System.getProperty("os.arch"))
        }
        putJsonObject("jvm") {
            put(VERSION, System.getProperty("java.version"))
            put("vendor", System.getProperty("java.vendor"))
        }
        put("topology", run.topology?.let(::topology) ?: JsonPrimitive(null as String?))
        put("checks", checks(run.sections))
        put("perf", perf(run.topology))
        if (withLogs) put("logs", logs())
    }

    private fun topology(t: Topology): JsonObject = buildJsonObject {
        putJsonObject("daemon") {
            put("control_port", t.daemon.controlPort)
            put("state_dir", t.daemon.stateDir?.let { redaction.text(it) })
            put("show_reasoning", t.daemon.showReasoning)
            put("effort", t.daemon.effort)
            put("mcp_hosting", t.daemon.mcpHosting)
            putJsonArray("mcp_hosting_exclude") {
                t.daemon.mcpHostingExclude.orEmpty().forEach { add(JsonPrimitive(it)) }
            }
        }
        putJsonObject("providers") { t.providers.forEach { (key, p) -> put(key, provider(p)) } }
        putJsonObject("heads") { t.heads.forEach { (key, h) -> put(key, head(h)) } }
    }

    private fun provider(p: ProviderConfig): JsonObject = buildJsonObject {
        put("dialect", p.dialect.name.lowercase())
        put("auth_kind", p.auth.kind)
        put("base_url_host", redaction.host(p.baseUrl))
        put("default_context_window", p.defaultContextWindow)
        put("extra_headers", p.extraHeaders.size)
        putJsonArray(MODELS) {
            p.models.forEach { m ->
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put(CONTEXT_WINDOW, m.contextWindow)
                    },
                )
            }
        }
        putJsonArray("window_rules") {
            p.windowRules.forEach { add(JsonPrimitive("${it.prefix}=${it.contextWindow}")) }
        }
        put("quirks", quirks(p))
    }

    /** The quirk knobs by name, only when set; a knob absent here is a knob the report does not know. */
    private fun quirks(p: ProviderConfig): JsonObject = buildJsonObject {
        val q = p.quirks
        put("code_mode", p.codeModeEnabled)
        put("cache_key", q.cacheKey)
        put("effort_ceiling", q.effortCeiling)
        put("store", q.store)
        put("tool_choice", q.toolChoice)
        put("account_id_header", q.accountIdHeader)
        listOf(
            "websocket" to q.webSocket,
            "reasoning_cache" to q.reasoningCache,
            "parallel_tool_calls" to q.parallelToolCalls,
            "zstd_request_body" to q.zstdRequestBody,
            "reasoning_effort" to q.reasoningEffort,
            "mfjs" to q.mfjs,
            "strip_cache_control" to q.stripCacheControl,
            "synthesize_signatures" to q.synthesizeSignatures,
            "map_thinking_adaptive" to q.mapThinkingAdaptive,
            "strip_sampling_params" to q.stripSamplingParams,
        ).forEach { (name, value) -> value?.let { put(name, it) } }
        q.compactEffort?.let { put("compact_effort", it) }
        q.toolSurface?.let { put("tool_surface", it.enabled) }
    }

    private fun head(h: HeadConfig): JsonObject = buildJsonObject {
        put("provider", h.provider)
        put("port", h.port)
        put("discovery_prefix", h.discoveryPrefix)
        put("pinned_model", h.pinnedModel)
        put(CONTEXT_WINDOW, h.contextWindow)
        putJsonArray(MODELS) { h.models.orEmpty().forEach { add(JsonPrimitive(it.id)) } }
    }

    private fun checks(sections: List<Pair<String, List<DoctorCheck>>>): JsonArray = buildJsonArray {
        sections.forEach { (section, checks) ->
            checks.forEach { c ->
                add(
                    buildJsonObject {
                        put("id", "$section/${c.name}")
                        put("status", c.status.name.lowercase())
                        put("detail", redaction.text(c.detail))
                        c.fix?.let { put("fix", redaction.text(it)) }
                    },
                )
            }
        }
    }

    /** Last [PERF_TAIL_ROWS] rows per configured head, allowlisted to the perf keys + model/outcome. */
    private fun perf(t: Topology?): JsonObject = buildJsonObject {
        t?.heads?.keys?.forEach { key -> put(key, perfRows(statePaths.perfStatsFile(key))) }
    }

    private fun perfRows(file: Path): JsonArray = buildJsonArray {
        Cancellables.runCatchingCancellable { JsonlSink.readTail(file, PERF_TAIL_BYTES) }
            .getOrDefault(emptyList())
            .takeLast(PERF_TAIL_ROWS)
            .mapNotNull { line ->
                Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
            .forEach { row ->
                add(buildJsonObject { row.filterKeys { it in PERF_FIELDS }.forEach { (k, v) -> put(k, v) } })
            }
    }

    private fun logs(): JsonArray = buildJsonArray {
        val file = statePaths.logsDir.resolve("daemon.log")
        val tail = Cancellables.runCatchingCancellable { LogFileSource(file).tail(LOG_TAIL_LINES) }.getOrDefault("")
        redaction.logLines(tail.lines()).forEach { add(JsonPrimitive(it)) }
    }
}
