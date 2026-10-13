// NEW: the doctor report's log tail is shareable, redacted diagnostics (spec §6; splice-lead, Oct 13): raw lines, but a
// line that carries a person's or a model's own words keeps its head and loses that payload, the way the other masks
// keep a line's shape and lose the secret.
//
// THE LINE KINDS ARE THE DAEMON'S OWN, found by a sweep of every log call in main source on Oct 13. Each marker below
// ends where one kind's payload begins:
//   upstream error bodies   RetryPolicy (encrypted_content, attempt n/m), FailureRules (rejected body, missing model)
//   failure text            TurnKnownEnd and TurnFinish (msg=), TurnEnding (unexpected), DriveSignals, ReanchorRunner
//   raw upstream bytes      TearAwareEvents (malformed SSE frame), ZeroEventFailure (snippet=)
//   websocket close text    WsRoundDriver (failed before any client frame), InboxListener (closed by the peer)
//   tool-call arguments     TurnPreparation (activity label answered locally)
// A line without the daemon's `[date time] [tag]` opening is not the daemon's: a stack frame or a child's stderr, which
// can quote anything, so it is masked whole.
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
).joinToString("|").toRegex()

internal class DoctorLogPayloads {
    private val daemonLine = Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}] \\[")

    /** [line] with the payload after its head masked, whole when it is not a daemon line, and as it was otherwise. */
    fun mask(line: String): String = when {
        line.isBlank() -> line
        !daemonLine.containsMatchIn(line) -> MASKED
        else -> PAYLOAD_STARTS.find(line)?.let { line.substring(0, it.range.last + 1) + MASKED } ?: line
    }
}
