// NEW: the doctor report's log tail is shareable, redacted diagnostics (spec §6; splice-lead, Oct 13): raw lines, but a
// line that carries a person's or a model's own words keeps its head and loses that payload, the way the other masks
// keep a line's shape and lose the secret.
//
// THE LINE KINDS ARE THE DAEMON'S OWN, found by a sweep of every log call in main source on Oct 13. Each marker below
// ends where one kind's payload begins:
//   upstream error bodies   RetryPolicy (encrypted_content, attempt n/m); older daemons' FailureRules (no retry: …)
//   failure text            TurnKnownEnd and TurnFinish (msg=), TurnEnding (unexpected), DriveSignals, ReanchorRunner
//   raw upstream bytes      TearAwareEvents (malformed SSE frame), ZeroEventFailure (snippet=)
//   websocket close text    WsRoundDriver (failed before any client frame), InboxListener (closed by the peer)
//   tool-call arguments     TurnPreparation (activity label answered locally)
//   exception text          AuthProbeLoop (tick threw, loop died), LocalRuntimeWatch and TurnPathProbeLoop (DIED),
//                           HeadProbes (failed to start), HeadShutdown (stop failed uncaught), BudgetLedger (spend
//                           short): a parse failure's message can quote the body it failed on (splice-lead, Oct 13)
// A line without the daemon's `[date time.millis] [tag]` opening is not trusted, so it is masked whole: a stack frame or
// a child's stderr can quote anything.
//
// A GENERATION IS TRUSTED WHOLE OR NOT AT ALL (pre-merge review, Oct 13). Before Oct 13, 2026 the stamp had no
// milliseconds and a record could span lines, so a legacy payload's continuation can itself read as a millisecond daemon
// line (one that quotes a log, say) and no marker below would catch it. The daemon now writes a record's own line breaks
// as \r and \n, so a generation that only that writer wrote holds nothing but millisecond lines, its first line
// included, and the writer rolls any daemon.log it did not start that way before its own first line (DaemonBoundary).
// A generation whose first line or any tail line breaks that shape may hold a legacy record, so every line in it keeps
// at most its stamp and a plain tag, and its words go.
package splice.diagnostics.doctor.report

private const val MASKED = "<redacted:text>"

private val PAYLOAD_STARTS: Regex = listOf(
    "encrypted_content error \\(no retry\\): ",
    "upstream \\d{3} attempt \\d+/\\d+: ",
    "\\(no retry: [^)]*\\): ",
    " msg=",
    "latency=\\d+ms : ",
    "malformed SSE frame skipped: ",
    " snippet=\"",
    "absorbed by re-anchor: ",
    "re-anchor \\d+: ",
    "websocket round failed before any client frame \\(",
    "socket closed by the peer \\(status=\\d+, ",
    "activity label answered locally: \"",
    "\\[auth-probe] probe tick threw: ",
    "\\[auth-probe] loop died: ",
    "LOCAL RUNTIME WATCH DIED \\(",
    "TURN PATH PROBE DIED \\(",
    "\\[boot] failed to start: ",
    "head stop failed uncaught: [^:]*: ",
    "today's spend before this daemon started is short: ",
).joinToString("|").toRegex()

internal class DoctorLogPayloads {
    private val stamp = "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}]"
    private val daemonLine = Regex("$stamp \\[")

    /** The stamp and, when it is a plain word, the first tag: all an untrusted generation's line keeps. */
    private val shape = Regex("$stamp(?: \\[[A-Za-z0-9_.:-]{1,40}])?")

    /** [generation]'s lines, each with its payload masked when the generation is trusted, else down to its shape. */
    fun mask(generation: GenerationTail): List<String> {
        val trusted = trusted(generation)
        return generation.lines.map { line ->
            when {
                line.isBlank() -> line
                trusted -> payloadMasked(line)
                else -> shape.find(line)?.let { "${it.value} $MASKED" } ?: MASKED
            }
        }
    }

    /** Only the escaping writer wrote it: its first line and every tail line open with the stamp and a tag. */
    private fun trusted(generation: GenerationTail): Boolean = daemonLine.containsMatchIn(generation.head) &&
        generation.lines.all { it.isBlank() || daemonLine.containsMatchIn(it) }

    private fun payloadMasked(line: String): String =
        PAYLOAD_STARTS.find(line)?.let { line.substring(0, it.range.last + 1) + MASKED } ?: line
}
