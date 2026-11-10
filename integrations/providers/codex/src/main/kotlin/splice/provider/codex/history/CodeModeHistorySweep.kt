// NEW: Nov 9, 2026 — a head's code-mode history journals go once their conversation has sat unused past the records'
// lifetime. Nothing removed them while code mode stayed on: 1,977 journals held 7 GB on one machine, back 15 days.
package splice.provider.codex.history

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.state.retention.CodeModeSweeps
import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** On [CodeModeSweeps]' timer, soon after the bridge starts and hourly after: a journal last written longer ago than
 *  the records' ttl goes, unless its conversation is in memory now. The history is held weakly, so a bridge nobody
 *  stopped (a test's) does not stay reachable through the timer; the sweep ends itself once it is gone. */
internal class CodeModeHistorySweep(history: CodeModeHistoryKept, private val config: CodeModeBridgeConfig) : Runnable {
    private val history = WeakReference(history)

    @Volatile private var running: ScheduledFuture<*>? = null

    fun start() {
        running = CodeModeSweeps.every(HISTORY_SWEEP_INTERVAL, HISTORY_SWEEP_FIRST, this)
    }

    fun stop() {
        running?.cancel(false)
    }

    override fun run() {
        val live = history.get() ?: return stop()
        Cancellables.runCatchingBestEffort {
            val swept = live.sweep(config.clock.millis() - config.retention.ttl.inWholeMilliseconds)
            if (swept != null && swept.journals > 0) {
                config.log(
                    "[code-mode] history sweep removed ${swept.journals} conversation file(s), ${swept.bytes} bytes, " +
                        "unused for more than ${config.retention.ttl}",
                )
            }
        }.exceptionOrNull()?.let { failure ->
            config.log(
                "[code-mode] history sweep of ${config.state.dir} failed (${SafeFailureText.render(failure)}); " +
                    "the next sweep tries again",
            )
        }
    }
}

// A restart (betas install through the day) must not postpone the sweep by a whole interval.
private val HISTORY_SWEEP_FIRST: Duration = 2.minutes

// Journals go by the hour at the finest: the ttl they are measured against is a day by default.
private val HISTORY_SWEEP_INTERVAL: Duration = 1.hours
