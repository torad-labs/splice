// NEW: V4-174 — how `splice trace` prints: a table of turns (one line each, newest last), one
// turn's records in full (`--turn`), or the raw JSON lines (`--json`, for jq). Labels say what was
// measured. Bodies are printed VERBATIM — they are the point of the trace — and only under
// `--turn`, so the table stays a table.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.perf.PerfKeys
import splice.core.terminal.BOLD
import splice.core.terminal.DIM
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.util.JsonScalars
import java.nio.file.Path
import java.time.Instant

// why: the same 8-character session tag the perf rows and log lines print (SESSION_TAG_CHARS)
private const val SESSION_COLUMN_CHARS = 8

internal class TraceView(private val output: TerminalOutput) {

    /** One line per turn: when, id, session, model, outcome, rounds/attempts, total ms. */
    fun printTable(head: String, traceDir: Path, turns: List<TracedTurn>, read: TraceRead): Boolean {
        val where = "$traceDir/$head-YYYY-MM-DD.jsonl"
        output.line(
            "${BOLD}splice trace $head$RESET $DIM— ${turns.size} of ${read.turns.size} turn(s) on disk, " +
                "oldest first ($where)$RESET",
        )
        if (read.turns.isEmpty()) {
            output.line(
                "  ${DIM}no turns traced yet — is [heads.$head.overrides] trace = true (restart required)?$RESET",
            )
        }
        turns.forEach { turn -> output.line("  " + line(turn)) }
        if (read.skippedLines > 0) {
            output.line("  $YELLOW${read.skippedLines} line(s) skipped (not a trace record)$RESET")
        }
        return true
    }

    /** Every record of one turn, in order, bodies verbatim. */
    fun printTurn(head: String, turn: TracedTurn): Boolean {
        output.line("${BOLD}splice trace $head --turn ${turn.id}$RESET $DIM— ${line(turn)}$RESET")
        turn.attempts.forEach { printAttempt(it) }
        val record = turn.turn
        if (record == null) {
            output.line("\n$YELLOW(no turn record yet — the turn has not ended, or its ending was lost)$RESET")
            return true
        }
        val client = record["client"]?.jsonObject
        val answer = record["answer"]?.jsonObject
        val method = JsonScalars.strOrEmpty(client?.get("method"))
        output.line("\n$BOLD── client request$RESET  $method ${JsonScalars.strOrEmpty(client?.get("path"))}")
        printHeaders(client)
        printBody(client, "body")
        val status = JsonScalars.strOrEmpty(answer?.get("status"))
        val stream = JsonScalars.strOrEmpty(answer?.get("stream"))
        output.line("\n$BOLD── answer to the client$RESET  status=$status stream=$stream")
        printBody(answer, "body")
        val outcome = JsonScalars.strOrEmpty(record["outcome"])
        output.line("\n$BOLD── outcome$RESET  $outcome  ${DIM}perf ${record["perf"]}$RESET")
        return true
    }

    /** The records as written, one JSON object per line, in order. */
    fun printJson(turns: List<TracedTurn>): Boolean {
        turns.forEach { turn ->
            turn.attempts.forEach { output.line(it.toString()) }
            turn.turn?.let { output.line(it.toString()) }
        }
        return true
    }

    private fun line(turn: TracedTurn): String {
        val first = turn.first
        val ts = Instant.ofEpochMilli(turn.ts)
        val session = turn.session?.take(SESSION_COLUMN_CHARS) ?: "-"
        val model = JsonScalars.strOrEmpty(first["model"])
        val compact = if (JsonScalars.str(first, "compact") == "true") " compact" else ""
        return "$ts  ${turn.id}  session=$session  model=$model$compact  ${ending(turn)}"
    }

    /** The outcome columns: from the turn record when the turn has ended, else what the attempts say. */
    private fun ending(turn: TracedTurn): String {
        val open = turn.attempts.size
        val record = turn.turn ?: return "(open)  rounds=$open attempts=$open  total=-ms"
        val marks = record["perf"]?.jsonObject?.get("marks")?.jsonObject
        val total = marks?.let { JsonScalars.str(it, PerfKeys.TOTAL) } ?: "-"
        return "${JsonScalars.strOrEmpty(record["outcome"])}  rounds=${JsonScalars.strOrEmpty(record["rounds"])} " +
            "attempts=${JsonScalars.strOrEmpty(record["attempts"])}  total=${total}ms"
    }

    private fun printAttempt(attempt: JsonObject) {
        val request = attempt["request"]?.jsonObject
        val response = attempt["response"]?.jsonObject
        val transport = JsonScalars.strOrEmpty(attempt["transport"])
        val status = response?.let { JsonScalars.str(it, "status") } ?: "-"
        val ended = JsonScalars.str(attempt, "failure")?.let { "  ${YELLOW}failure: $it$RESET" }.orEmpty()
        output.line(
            "\n$BOLD── upstream attempt ${JsonScalars.strOrEmpty(attempt["attempt"])}$RESET  " +
                "round=${JsonScalars.strOrEmpty(attempt["round"])} $transport status=$status " +
                "${JsonScalars.strOrEmpty(attempt["durationMs"])}ms  ${JsonScalars.strOrEmpty(attempt["url"])}$ended",
        )
        output.line("${DIM}request headers$RESET")
        printHeaders(request)
        output.line("${DIM}request body${encoding(request)}$RESET")
        printBody(request, "body")
        if (response != null) {
            output.line("${DIM}response headers$RESET")
            printHeaders(response)
            output.line("${DIM}response text$RESET")
            printBody(response, "text")
        }
    }

    private fun encoding(request: JsonObject?): String =
        request?.let { JsonScalars.str(it, "encoding") }?.let { " (sent $it-encoded)" }.orEmpty()

    private fun printHeaders(record: JsonObject?) {
        record?.get("headers")?.jsonObject?.forEach { (name, value) ->
            output.line("  $name: ${JsonScalars.strOrEmpty(value)}")
        }
    }

    private fun printBody(record: JsonObject?, key: String) {
        output.line(JsonScalars.strOrEmpty(record?.get(key)))
        if (record != null && JsonScalars.str(record, "truncated") == "true") {
            output.line("$YELLOW[truncated at traceMaxBodyChars]$RESET")
        }
    }
}
