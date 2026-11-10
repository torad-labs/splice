// NEW: 2026-11-09 audit — a head refusing every request reads degraded. From 5:51 to 6:58 PM CT that day the Responses
// dialect refused every claudex turn on a field Claude Code 2.1.321 had started sending, and /health, the status table
// and daemon.log all read a quiet head. Each refusal is recorded on its own since 4d4cf1110; this is the head-level
// reading beside them: enough refusals in a row for one cause, with no turn admitted since, mark the head degraded
// where the operator looks, and the next admitted turn clears it.
package splice.head.admission

import splice.core.head.RefusingReading
import splice.core.util.LogSink
import splice.core.util.WallClock

/** One head's run of requests refused before any upstream turn, since it last admitted one. */
internal class RefusalStreak(private val head: String, private val log: LogSink, private val wall: WallClock) {
    private var inRow = 0
    private var sinceEpochMs = 0L
    private var cause: String? = null
    private var sameCause = 0
    private var said = ""
    private var degraded = false

    /** A request refused before any upstream turn: [sentence] is the line its client reads, which is what makes two
     *  refusals one cause; [detail] is what splice saw beyond it (a field's name, a defect's kind), for the reading. */
    @Synchronized
    fun refused(sentence: String, detail: String?) {
        if (inRow == 0) sinceEpochMs = wall()
        inRow++
        if (sentence == cause) sameCause++ else sameCause = 1
        cause = sentence
        said = detail?.let { "$it: $sentence" } ?: sentence
        if (degraded || sameCause < DEGRADED_AFTER) return
        degraded = true
        log(
            "[$head] HEAD DEGRADED: $sameCause requests in a row refused before any upstream turn, " +
                "the last for: $said. /health lists it under refusingHeads until a turn is admitted.\n",
        )
    }

    /** A turn was admitted: the run ends, and a degraded head says it is serving again. */
    @Synchronized
    fun admitted() {
        if (degraded) log("[$head] head admits turns again after $inRow refused in a row (the last: $said)\n")
        inRow = 0
        sameCause = 0
        cause = null
        said = ""
        degraded = false
    }

    /** The degraded reading, or null while the head admits turns. */
    @Synchronized
    fun reading(): RefusingReading? = if (degraded) RefusingReading(said, inRow, sinceEpochMs) else null
}

// why: a client sends each user turn and its side queries as their own requests, so five refusals for one cause with
// nothing admitted between them is a minute or two of a session refused, not one bad request (the 2026-11-09 outage
// refused 68 in 58 minutes); a single client's odd request refused once or twice stays a line in the log.
private const val DEGRADED_AFTER = 5
