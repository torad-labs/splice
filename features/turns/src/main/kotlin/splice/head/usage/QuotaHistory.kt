// NEW: each change in a head's plan reading, one line per change, beside the latest reading's file. The latest
// file answers how full the plan is now; this one answers when it got there, so the tokens a stretch of work sent
// (the perf rows of the same hours) can be set against the share of the plan the provider says they used. On
// Nov 16, 2026 a plan's week rose steeply within a few hours and nothing on disk could say which hour
// took which points: only the newest reading was kept.
package splice.head.usage

import kotlinx.serialization.json.JsonPrimitive
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Path

// why: a line is about 200 bytes and one is written per point a plan moves, so this holds years of a single login
// and weeks of a head with many; JsonlSink keeps one rolled generation beyond it.
private const val QUOTA_HISTORY_MAX_BYTES = 4L * 1024 * 1024

/**
 * One login's readings in its head's history file. A line is written when the reading moves: a window's used
 * share changes, a model's week does, or a window appears or goes. A reading that repeats the one before it
 * writes nothing, so a busy head adds a line per point of its plan and not one per turn.
 *
 * A line is `{"login": <label>, "reading": <the latest reading's file text>}`: the windows, the plan, the time the
 * provider was read and the account the reading names. The first reading after a start always writes, because
 * what the last run saw is not known here.
 *
 * [headQuotaFile] is the head's own latest-reading file (`<head>-quota.json`); the history sits beside it as
 * `<head>-quota-history.jsonl`, one file for every login of the head.
 */
public class QuotaHistory(
    private val headQuotaFile: Path,
    private val login: String,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val file: Path =
        headQuotaFile.resolveSibling("${headQuotaFile.fileName.toString().removeSuffix(".json")}-history.jsonl")
    private var last: QuotaSnapshot? = null

    /** The same file under another login's name: a login added to a running head writes where its head does. */
    public fun forLogin(other: String): QuotaHistory = QuotaHistory(headQuotaFile, other, log)

    /** [text] is what the latest reading's file was just given for [reading]. */
    @Synchronized
    public fun kept(reading: QuotaSnapshot, text: String) {
        if (reading.isEmpty) return
        val before = last
        last = reading
        if (before != null && sameStanding(before, reading)) return
        val line = "{\"login\":${JsonPrimitive(login)},\"reading\":$text}"
        Cancellables.runCatchingCancellable { JsonlSink.appendLine(file, line, QUOTA_HISTORY_MAX_BYTES) }
            .onFailure { log("[quota] ${file.fileName} write FAILED (${SafeFailureText.render(it)})\n") }
    }

    private fun sameStanding(a: QuotaSnapshot, b: QuotaSnapshot): Boolean =
        used(a.fiveHour) == used(b.fiveHour) && used(a.sevenDay) == used(b.sevenDay) &&
            a.models.map { it.model to it.usedPercent } == b.models.map { it.model to it.usedPercent }

    private fun used(window: QuotaWindow?): Double? = window?.usedPercent
}
