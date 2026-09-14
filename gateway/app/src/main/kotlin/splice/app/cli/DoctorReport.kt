// NEW: v0.4.0 FEATURES.md §6 — `splice doctor --json [--with-logs] [--out FILE]` — the shareable
// bug report. Schema version 1. Emission is an ALLOWLIST: every key below is named here, so a new
// topology or perf field is absent until someone adds it; account ids, e-mails, tokens and the
// session cwd have no key to ride. Free text (check details, log lines) goes through
// DoctorRedaction. The perf tail is the last 200 rows per head across both file generations,
// restricted to the numeric perf keys (numbers only) plus model and outcome (strings, redacted).
// Nothing is uploaded: the report is printed, or written to --out, and with --with-logs the log
// lines are shown on the terminal before the file is written.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.GATEWAY_VERSION
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private const val SCHEMA_VERSION = 1
private const val VERSION = "version"

/** What a JVM/OS property may look like in the report: a bounded plain token, else omitted. */
private val SYSTEM_VALUE = Regex("^[A-Za-z0-9][A-Za-z0-9 ._+-]{0,63}$")
private const val OMITTED = "<omitted>"
internal class DoctorReport(
    envReader: EnvReader = EnvReader(System::getenv),
    private val claudeVersion: ClaudeVersionRead,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val statePaths: StatePaths = StatePaths(envReader = envReader),
    private val installPaths: InstallPaths = InstallPaths(envReader = envReader),
    private val redaction: DoctorRedaction = DoctorRedaction(
        home,
        listOf(statePaths.stateDir, statePaths.logsDir, installPaths.binDir, installPaths.shareDir),
    ),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val files = DoctorReportFiles(redaction)
    private val perfTail = DoctorReportPerf(statePaths, files, json)
    private val tail = DoctorReportTail(statePaths, redaction, files)

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

    /** A system property is overridable on the JVM command line: it passes the same redaction as
     *  every other string and is emitted only when it is a bounded plain token. */
    private fun system(key: String): String =
        System.getProperty(key)?.let(redaction::text)?.takeIf { SYSTEM_VALUE.matches(it) } ?: OMITTED

    internal fun build(run: DoctorRun, withLogs: Boolean): JsonObject = buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("generated_at", Instant.now().toString())
        putJsonObject("splice") { put(VERSION, GATEWAY_VERSION) }
        putJsonObject("claude_code") { put(VERSION, redaction.text(claudeVersion())) }
        putJsonObject("os") {
            put("name", system("os.name"))
            put(VERSION, system("os.version"))
            put("arch", system("os.arch"))
        }
        putJsonObject("jvm") {
            put(VERSION, system("java.version"))
            put("vendor", system("java.vendor"))
        }
        val names = SafeNames(redaction, run.topology, run.accountPools)
        val shape = DoctorReportShape(redaction, names)
        put("topology", run.topology?.let(shape::topology) ?: JsonPrimitive(null as String?))
        put("checks", shape.checks(run.sections))
        put("accounts", shape.accounts(run.accountPools))
        put("perf", perfTail.perf(run.topology, names))
        if (withLogs) {
            val logs = tail.logs(names)
            put("logs", logs.lines)
            put("logs_dropped_in_tail", logs.dropped)
            logs.error?.let { put("logs_error", it) }
        }
    }
}
