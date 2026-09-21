// NEW: V4-174 — `splice trace <head> [--last N] [--session S] [--turn ID] [--json] [--purge]`: the
// operator's read of a head's full request/response trace, from the day files and with no daemon
// (the perf/logs idiom). The head is checked against the topology first so a typo names the heads
// that exist instead of printing an empty trace. In splice.app.cli.trace, beside cli.wire, because
// splice.app.cli sits at the concentration ceiling (V4-173's own gate said so).
package splice.app.cli.trace

import splice.app.daemon.TopologyLoader
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal const val TRACE_USAGE =
    "usage: splice trace <head> [--last N] [--session S] [--turn ID] [--json] [--purge]"

// why: a screenful of turns when no --last is given; the whole day is a jq job, not a table
private const val DEFAULT_LAST = 20

internal data class TraceOpts(
    val head: String,
    val last: Int = DEFAULT_LAST,
    val session: String? = null,
    val turn: String? = null,
    val json: Boolean = false,
    val purge: Boolean = false,
)

internal class TraceCommand(private val rows: TraceRows = TraceRows(), private val view: TraceView = TraceView()) {

    internal fun trace(args: List<String>, envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val opts = parseTraceArgs(args)
            ?: return fail("unknown or malformed arguments ${args.joinToString(" ")}\n$TRACE_USAGE")
        if (!headExists(opts.head, envReader)) return false
        val traceDir = StatePaths(envReader = envReader).traceDir
        return if (opts.purge) purge(opts.head, traceDir) else show(opts, traceDir)
    }

    private fun show(opts: TraceOpts, traceDir: Path): Boolean {
        val read = rows.read(traceDir, opts.head)
        val selected = read.turns
            .filter { opts.session == null || it.session?.startsWith(opts.session) == true }
            .filter { opts.turn == null || it.id == opts.turn }
        return when {
            opts.turn != null && selected.isEmpty() -> fail("no turn ${opts.turn} in ${opts.head}'s trace")
            opts.json -> view.printJson(selected.takeLast(opts.last))
            opts.turn != null -> view.printTurn(opts.head, selected.single())
            else -> view.printTable(opts.head, traceDir, selected.takeLast(opts.last), read)
        }
    }

    /** Deletes the head's day files and says what went; nothing else in the directory is touched. */
    private fun purge(head: String, traceDir: Path): Boolean {
        val gone = rows.days(traceDir, head).purge()
        if (gone.isEmpty()) {
            println("splice trace: nothing to purge — no trace files for $head under $traceDir")
        } else {
            println("splice trace: purged ${gone.size} day file(s) of $head:")
            gone.forEach { println("  $it") }
        }
        return true
    }

    /** The head must be configured; a misspelt one is refused with the heads that exist. */
    private fun headExists(head: String, envReader: EnvReader): Boolean {
        val path = TopologyLoader.configPath(envReader)
        val topology = try {
            TopologyLoader.parse(Files.readString(path))
        } catch (unreadable: IOException) {
            return fail("cannot read $path: ${SafeFailureText.render(unreadable)}")
        }
        if (head in topology.heads) return true
        return fail("no head named '$head' in $path — heads: ${topology.heads.keys.joinToString(", ")}")
    }

    private fun fail(message: String): Boolean {
        System.err.println("splice trace: $message")
        return false
    }

    internal fun parseTraceArgs(args: List<String>): TraceOpts? {
        var opts = TraceOpts(head = "")
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val takesValue = arg in VALUE_FLAGS
            opts = applyArg(opts, arg, if (takesValue) args.getOrNull(i + 1) else null) ?: return null
            i += if (takesValue) 2 else 1
        }
        return opts.takeIf { it.head.isNotEmpty() }
    }

    /** One argument folded into the options; null refuses it (a missing value, a second head, an
     *  unknown flag, a count that is not positive). */
    private fun applyArg(opts: TraceOpts, arg: String, value: String?): TraceOpts? = when (arg) {
        "--json" -> opts.copy(json = true)
        "--purge" -> opts.copy(purge = true)
        in VALUE_FLAGS -> value?.let { applyValue(opts, arg, it) }
        else -> if (opts.head.isEmpty() && !arg.startsWith("-")) opts.copy(head = arg) else null
    }

    private fun applyValue(opts: TraceOpts, flag: String, value: String): TraceOpts? = when (flag) {
        "--last" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { opts.copy(last = it) }
        "--session" -> opts.copy(session = value).takeIf { value.isNotBlank() }
        else -> opts.copy(turn = value).takeIf { value.isNotBlank() }
    }
}

private val VALUE_FLAGS = setOf("--last", "--session", "--turn")
