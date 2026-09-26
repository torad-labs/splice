// NEW: V4-174 — `splice trace <head> [--last N] [--session S] [--turn ID] [--json] [--purge]`: the
// operator's read of a head's full request/response trace, from the day files and with no daemon
// (the perf/logs idiom). The head is checked against the topology first so a typo names the heads
// that exist instead of printing an empty trace. A turns slice beside the TraceStore that writes
// these files (LAYOUT-01): app supplies the topology read and the two terminal streams.
package splice.head.trace

import splice.core.storage.DayPurge
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Path

internal const val TRACE_USAGE =
    "usage: splice trace <head> [--last N] [--session S] [--turn ID] [--json] [--purge]"

// why: a screenful of turns when no --last is given; the whole day is a jq job, not a table. The
// console's list takes the same default (TraceRoute).
internal const val DEFAULT_LAST = 20

internal data class TraceOpts(
    val head: String,
    val last: Int = DEFAULT_LAST,
    val session: String? = null,
    val turn: String? = null,
    val json: Boolean = false,
    val purge: Boolean = false,
)

/** `splice trace`. [output] is stdout, [errors] stderr: `--json | jq` must never read a refusal. */
public class TraceCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val heads: TraceHeadSource,
    private val traceDirs: TraceDirSource,
) {
    private val rows = TraceRows()
    private val view = TraceView(output)

    public fun trace(args: List<String>, envReader: EnvReader): Boolean {
        val opts = parseTraceArgs(args)
            ?: return fail("unknown or malformed arguments ${args.joinToString(" ")}\n$TRACE_USAGE")
        if (!headExists(opts.head, envReader)) return false
        val traceDir = traceDirs.traceDir(envReader)
        return if (opts.purge) purge(opts.head, traceDir) else show(opts, traceDir)
    }

    private fun show(opts: TraceOpts, traceDir: Path): Boolean {
        // V4-286: a trace dir or day that cannot be read is said, never an empty table blaming the knob.
        val read = Cancellables.runCatchingCancellable { rows.read(traceDir, opts.head) }
            .getOrElse { failure ->
                return fail("cannot read ${opts.head}'s trace under $traceDir: ${SafeFailureText.render(failure)}")
            }
        val selected = read.selected(opts.session, opts.turn)
        return when {
            opts.turn != null && selected.isEmpty() -> fail("no turn ${opts.turn} in ${opts.head}'s trace")
            opts.json -> view.printJson(selected.takeLast(opts.last))
            opts.turn != null -> view.printTurn(opts.head, selected.single())
            else -> view.printTable(opts.head, traceDir, selected.takeLast(opts.last), read)
        }
    }

    /** Deletes the head's day files and says what went and what stayed; nothing else in the directory
     *  is touched. A file that stayed, or a directory that could not be listed, fails the command. */
    private fun purge(head: String, traceDir: Path): Boolean =
        when (val purge = rows.days(traceDir, head).purge()) {
            is DayPurge.Unlisted ->
                fail("cannot list $traceDir: ${SafeFailureText.render(purge.failure)}; nothing was purged")
            is DayPurge.Listed -> {
                if (purge.deleted.isEmpty() && purge.failed.isEmpty()) {
                    output.line("splice trace: nothing to purge; no trace files for $head under $traceDir")
                }
                if (purge.deleted.isNotEmpty()) {
                    output.line("splice trace: purged ${purge.deleted.size} day file(s) of $head:")
                    purge.deleted.forEach { output.line("  $it") }
                }
                purge.failed.forEach { (file, failure) ->
                    errors.line("splice trace: could not delete $file: ${SafeFailureText.render(failure)}")
                }
                purge.failed.isEmpty()
            }
        }

    /** The head must be configured; a misspelt one is refused with the heads that exist. */
    private fun headExists(head: String, envReader: EnvReader): Boolean =
        when (val configured = heads.load(envReader)) {
            is TraceHeads.Unreadable ->
                fail("cannot read ${configured.path}: ${SafeFailureText.render(configured.failure)}")
            is TraceHeads.Configured -> {
                val names = configured.names.joinToString(", ")
                head in configured.names || fail("no head named '$head' in ${configured.path}; heads: $names")
            }
        }

    private fun fail(message: String): Boolean {
        errors.line("splice trace: $message")
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

    /** A session or turn value is an id: a blank one, or a flag (`--turn --help`), is refused rather
     *  than filtered on, so a flag is never read as the id it was meant to follow. */
    private fun applyValue(opts: TraceOpts, flag: String, value: String): TraceOpts? = when (flag) {
        "--last" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { opts.copy(last = it) }
        "--session" -> opts.copy(session = value).takeIf { isId(value) }
        else -> opts.copy(turn = value).takeIf { isId(value) }
    }

    private fun isId(value: String): Boolean = value.isNotBlank() && !value.startsWith("--")
}

private val VALUE_FLAGS = setOf("--last", "--session", "--turn")
