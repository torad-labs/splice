package splice.app.sources.observability.cursor

import splice.core.util.MonoClock

internal class PerfCursorWork {
    private val started = MonoClock.nowMs()
    var bytes = 0L
        private set

    fun charge(count: Long) {
        val over = count > PERF_CURSOR_WORK_BYTES - bytes || MonoClock.nowMs() - started > QUERY_LIMIT_MS
        if (count < 0 || over) {
            throw PerfReadRefusal(PerfReadIssue.CURSOR_WORK_LIMIT)
        }
        bytes += count
    }
}

// Stop cursor scan work after ten seconds so one traversal cannot occupy the query lane indefinitely.
private const val QUERY_LIMIT_MS = 10_000L
