// NEW: v0.4.0 FEATURES.md §3 — `splice perf [--window 1h|24h|7d]` — the windowed summary per
// head, printed from the perf files directly (no daemon needed), the same numbers /api/perf/summary
// serves. Labels say what was measured, never why. Read-only: the topology is parsed, never
// materialized (no starter file from a diagnostic); without one there is nothing to list. A usage slice
// since LAYOUT-01, beside the PerfSummary it prints: app hands in each head's rows (the same file
// reader /api/perf uses), and the lines leave through TerminalOutput.
package splice.usage.perf

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.terminal.BOLD
import splice.core.terminal.DIM
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

// why: a fraction becomes a percentage.
private const val PERCENT = 100.0

// why: the widest model id the perf table column holds before it wraps.
private const val MODEL_PAD = 24

private const val USAGE = "usage: splice perf [--window 1h|24h|7d]"

/** One head's perf rows, by key: app hands in the day-file reader the daemon's own /api/perf route uses. */
public fun interface HeadPerfRows {
    public fun rows(head: String, env: EnvReader): PerfRowsSource
}

/** `splice perf`. [output] is the summary, [errors] the refusals and the missing-topology reason. */
public class PerfCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val rows: HeadPerfRows,
) {

    public fun perf(args: List<String>, envReader: EnvReader): Boolean {
        val summary = PerfSummary()
        val window = windowLabel(args)?.let(summary::window)
        if (window == null) {
            errors.line("splice perf: unknown or malformed arguments ${args.joinToString(" ")}\n$USAGE")
            return false
        }
        val heads = readHeads(envReader) ?: return false
        output.line("${BOLD}splice perf$RESET$DIM: last ${window.label} per head, from the perf files$RESET")
        heads.forEach { key ->
            val s = summary.summarize(rows.rows(key, envReader), window)
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
        errors.line("splice perf: $why")
        return null
    }

    /** Exactly `[]` or `[--window, <label>]`: a bare flag or a stray argument is refused, never defaulted. */
    private fun windowLabel(args: List<String>): String? = when {
        args.isEmpty() -> PerfWindow.H24.label
        args.size == 2 && args[0] == "--window" -> args[1]
        else -> null
    }

    private fun printHead(key: String, s: JsonObject) {
        output.line("")
        val count = num(s, "count")
        output.line("  $BOLD$key$RESET  $DIM$count turn(s), ${num(s, "local_steps")} local step(s)$RESET" + note(s))
        if (JsonScalars.str(s, "empty") == "true") {
            output.line("  $DIM–  no turns in this window$RESET")
            return
        }
        output.line("  time before first byte  ${pct(s["time_before_first_byte_ms"])}")
        output.line("  time streaming          ${pct(s["time_streaming_ms"])}")
        output.line("  total                   ${pct(s["total_ms"])}")
        val shares = (s["failure_shares"] as? JsonObject).orEmpty()
        val outcomes = s.getValue("outcomes").jsonObject.entries.joinToString(" ") { (k, v) ->
            "$k=${JsonScalars.strOrEmpty(v)}" + shares[k]?.let { " (${share(it)})" }.orEmpty()
        }
        output.line("  outcomes                $outcomes  (failure share ${share(s["failure_share"])})")
        kinds(s)?.let { output.line("  kinds                   $it") }
        models(s).forEach { output.line("  model $it") }
        output.line("  retries / refreshes     ${num(s, "retries")} / ${num(s, "refreshes")}")
        output.line("  cache hit ratio         ${share(s["cache_hit_ratio"])}")
        output.line("  peak inflight           ${num(s, "peak_inflight")}")
        val dropped = num(s, "telemetry_dropped")
        val writes = num(s, "io_drops_in_window")
        output.line("  telemetry dropped       $dropped row(s), at least $writes write(s) lost (absent from the file)")
    }

    /** Only the kinds that occurred, so a head with no compaction does not print a zero for one. */
    private fun kinds(s: JsonObject): String? = (s["kinds"] as? JsonObject)?.entries
        ?.filter { (_, v) -> JsonScalars.strOrEmpty(v) != "0" }
        ?.joinToString(" ") { (k, v) -> "$k=${JsonScalars.strOrEmpty(v)}" }
        ?.takeIf { it.isNotEmpty() }

    /** One line per model: its turns, failure share and total latency. */
    private fun models(s: JsonObject): List<String> =
        (s["models"] as? JsonObject).orEmpty().entries.map { (model, stats) ->
            val m = stats as JsonObject
            "${model.padEnd(MODEL_PAD)} ${num(m, "count")} turn(s), failure ${share(m["failure_share"])}, " +
                "total ${pct(m["total_ms"])}"
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
