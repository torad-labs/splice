// NEW (v0.4.0, FEATURES.md §3): `splice perf [--window 1h|24h|7d]` — the windowed summary per
// head, printed from the perf files directly (no daemon needed), the same numbers /api/perf/summary
// serves. Labels say what was measured, never why.
package splice.app.cli

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.app.PerfRowsFileSource
import splice.app.TopologyLoader
import splice.control.api.PerfSummary
import splice.control.api.PerfWindow
import splice.core.config.StatePaths
import splice.core.util.EnvReader

private const val PERCENT = 100.0

internal class PerfCommand {

    internal fun perf(args: List<String>, envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val summary = PerfSummary()
        val label = args.getOrNull(args.indexOf("--window") + 1).takeIf { "--window" in args }
        val window = summary.window(label ?: PerfWindow.H24.label)
        if (window == null) {
            System.err.println("splice perf: unknown window '$label'\nusage: splice perf [--window 1h|24h|7d]")
            return false
        }
        val heads = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(envReader)).heads.keys
        val statePaths = StatePaths(envReader = envReader)
        println("${BOLD}splice perf$RESET $DIM— last ${window.label} per head, from the perf files$RESET")
        heads.forEach { key ->
            val s = summary.summarize(PerfRowsFileSource(statePaths.perfStatsFile(key)), window)
            printHead(key, s)
        }
        return true
    }

    private fun printHead(key: String, s: JsonObject) {
        println()
        val count = s.getValue("count").jsonPrimitive.content
        println("  $BOLD$key$RESET  $DIM$count turn(s)$RESET" + note(s))
        if (s.getValue("empty").jsonPrimitive.content == "true") {
            println("  $DIM–  no rows in this window$RESET")
            return
        }
        println("  time before first byte  ${pct(s["time_before_first_byte_ms"])}")
        println("  time streaming          ${pct(s["time_streaming_ms"])}")
        println("  total                   ${pct(s["total_ms"])}")
        val outcomes = s.getValue("outcomes").jsonObject.entries.joinToString(" ") { (k, v) ->
            "$k=${v.jsonPrimitive.content}"
        }
        println("  outcomes                $outcomes  (failure share ${share(s["failure_share"])})")
        println("  retries / refreshes     ${num(s, "retries")} / ${num(s, "refreshes")}")
        println("  cache hit ratio         ${share(s["cache_hit_ratio"])}")
        println("  peak inflight           ${num(s, "peak_inflight")}")
        println("  telemetry dropped       ${num(s, "telemetry_dropped")} row(s)")
    }

    private fun note(s: JsonObject): String =
        (s["note"] as? JsonPrimitive)?.takeIf { it.isString }?.let { "  $YELLOW${it.content}$RESET" }.orEmpty()

    private fun num(s: JsonObject, key: String): String = (s[key] as? JsonPrimitive)?.content ?: "-"

    private fun pct(stats: JsonElement?): String {
        val obj = stats as? JsonObject ?: return "-"
        return "p50 ${num(obj, "p50")} ms · p95 ${num(obj, "p95")} ms"
    }

    private fun share(value: JsonElement?): String =
        (value as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { "%.1f%%".format(it * PERCENT) } ?: "-"
}
