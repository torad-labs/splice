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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private const val SCHEMA_VERSION = 1
private const val VERSION = "version"
internal class DoctorReport(
    envReader: EnvReader = EnvReader(System::getenv),
    private val claudeVersion: ClaudeVersionRead,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val statePaths: StatePaths = StatePaths(envReader = envReader),
    private val redaction: DoctorRedaction = DoctorRedaction(home),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val shape = DoctorReportShape(redaction)
    private val tail = DoctorReportTail(statePaths, redaction, json)

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
        put("topology", run.topology?.let(shape::topology) ?: JsonPrimitive(null as String?))
        put("checks", checks(run.sections))
        put("perf", tail.perf(run.topology))
        if (withLogs) put("logs", tail.logs())
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
}
