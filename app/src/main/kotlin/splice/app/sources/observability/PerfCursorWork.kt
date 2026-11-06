package splice.app.sources.observability

import splice.core.util.MonoClock
import java.io.IOException

internal class PerfCursorWork {
    private val started = MonoClock.nowMs()
    var bytes = 0L
        private set

    fun charge(count: Long) {
        val over = count > PERF_CURSOR_WORK_BYTES - bytes || MonoClock.nowMs() - started > QUERY_LIMIT_MS
        if (count < 0 || over) {
            throw IOException("cursor_work_limit")
        }
        bytes += count
    }
}

private const val QUERY_LIMIT_MS = 10_000L
