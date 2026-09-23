// NEW: v0.4.0 FEATURES.md §3 — `splice perf [--window 1h|24h|7d]` — the windowed summary per
// head, printed from the perf files directly (no daemon needed), the same numbers /api/perf/summary
// serves. Labels say what was measured, never why. Read-only: the topology is parsed, never
// materialized (no starter file from a diagnostic); without one there is nothing to list.
package splice.app.cli.status

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.app.daemon.TopologyLoader
import splice.app.sources.PerfRowsFileSource
import splice.control.api.usage.PerfSummary
import splice.control.api.usage.PerfWindow
import splice.core.config.StatePaths
import splice.core.terminal.BOLD
import splice.core.terminal.DIM
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.YELLOW
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

private const val PERCENT = 100.0
private const val USAGE = "usage: splice perf [--window 1h|24h|7d]"

internal class PerfCommand {

    internal fun perf(args: List<String>, envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val summary = PerfSummary()
        val window = windowLabel(args)?.let(summary::window)
        if (window == null) {
            System.err.println("splice perf: unknown or malformed arguments ${args.joinToString(" ")}\n$USAGE")
            return false
        }
        val heads = readHeads(envReader) ?: return false
        val statePaths = StatePaths(envReader = envReader)
        println("${BOLD}splice perf$RESET $DIM— last ${window.label} per head, from the perf files$RESET")
        heads.forEach { key ->
            val s = summary.summarize(PerfRowsFileSource(statePaths.perfStatsFile(key)), window)
            printHead(key, s)
        }
        return true
    }

    /** The configured head keys, read-only; null (with the reason on stderr) when there is no topology. */
    private fun readHeads(envReader: EnvReader): Set<String>? {
        val path = TopologyLoader.configPath(envReader)
        return try {
            TopologyLoader.parse(Files.readString(path)).heads.keys
        } catch (unreadable: IOException) {
            noTopology("no topology at $path (${SafeFailureText.render(unreadable)}); run splice init or splice add")
        } catch (ignored: IllegalArgumentException) {
            noTopology("the topology at $path does not parse; run splice doctor")
        } catch (ignored: IllegalStateException) {
            noTopology("the topology at $path does not parse; run splice doctor")
        }
    }

    private fun noTopology(why: String): Set<String>? {
        System.err.println("splice perf: $why")
        return null
    }

    /** Exactly `[]` or `[--window, <label>]`: a bare flag or a stray argument is refused, never defaulted. */
    private fun windowLabel(args: List<String>): String? = when {
        args.isEmpty() -> PerfWindow.H24.label
        args.size == 2 && args[0] == "--window" -> args[1]
        else -> null
    }

    private fun printHead(key: String, s: JsonObject) {
        println()
        val count = num(s, "count")
        println("  $BOLD$key$RESET  $DIM$count turn(s)$RESET" + note(s))
        if (JsonScalars.str(s, "empty") == "true") {
            println("  $DIM–  no rows in this window$RESET")
            return
        }
        println("  time before first byte  ${pct(s["time_before_first_byte_ms"])}")
        println("  time streaming          ${pct(s["time_streaming_ms"])}")
        println("  total                   ${pct(s["total_ms"])}")
        val shares = (s["failure_shares"] as? JsonObject).orEmpty()
        val outcomes = s.getValue("outcomes").jsonObject.entries.joinToString(" ") { (k, v) ->
            "$k=${JsonScalars.strOrEmpty(v)}" + shares[k]?.let { " (${share(it)})" }.orEmpty()
        }
        println("  outcomes                $outcomes  (failure share ${share(s["failure_share"])})")
        println("  retries / refreshes     ${num(s, "retries")} / ${num(s, "refreshes")}")
        println("  cache hit ratio         ${share(s["cache_hit_ratio"])}")
        println("  peak inflight           ${num(s, "peak_inflight")}")
        val drops = num(s, "io_drops_in_window")
        println("  file-io drops           at least $drops in this window (a dropped row is absent)")
    }

    /** The clamp / no-rows note, and a read failure — a broken instrument is said, never blank. */
    private fun note(s: JsonObject): String {
        val note = (s["note"] as? JsonPrimitive)?.takeIf { it.isString }?.let { "  $YELLOW${it.content}$RESET" }
        val error = (s["read_error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { "  $RED! ${it.content}$RESET" }
        return note.orEmpty() + error.orEmpty()
    }

    private fun num(s: JsonObject, key: String): String = JsonScalars.str(s, key) ?: "-"

    /** Every percentile with its denominator: three fast successes among thousands of failed turns
     *  read as n=3, not as the head's latency. */
    private fun pct(stats: JsonElement?): String {
        val obj = stats as? JsonObject ?: return "-"
        return "p50 ${num(obj, "p50")} ms · p95 ${num(obj, "p95")} ms · max ${num(obj, "max")} ms " +
            "(n=${num(obj, "count")})"
    }

    private fun share(value: JsonElement?): String =
        JsonScalars.str(value)?.toDoubleOrNull()?.let { String.format(Locale.ROOT, "%.1f%%", it * PERCENT) }
            ?: "-"
}
