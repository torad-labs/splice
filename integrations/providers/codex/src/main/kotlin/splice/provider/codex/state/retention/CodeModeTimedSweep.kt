package splice.provider.codex.state.retention

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeExpiredHistory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** V4-287: the timer every code-mode registry sweeps on, one named daemon thread for all heads. */
internal object CodeModeSweeps {
    // The platform factory, as AsyncFileIo's lane: this timer owns only its name and daemon-ness.
    private val threads = Executors.defaultThreadFactory()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        threads.newThread(task).apply {
            name = "splice-code-mode-sweep"
            isDaemon = true
        }
    }.apply { removeOnCancelPolicy = true }

    /** Runs [sweep] every [interval] until the returned future is cancelled. [sweep] must not throw:
     *  an exception ends a periodic task in silence. */
    fun every(interval: Duration, sweep: Runnable): ScheduledFuture<*> {
        val ms = interval.inWholeMilliseconds
        return timer.scheduleWithFixedDelay(sweep, ms, ms, TimeUnit.MILLISECONDS)
    }
}

/** V4-287: one registry's sweep on [CodeModeSweeps]' timer, running while the registry keeps any
 *  record. It holds the registry's [monitor] for each sweep; [save] writes the registry's state. */
internal class CodeModeTimedSweep(
    private val monitor: ReentrantLock,
    private val records: MutableList<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val save: Runnable,
    private val store: CodexCodeModeStore,
    private val config: CodeModeBridgeConfig,
    private val interval: Duration,
) {
    private val lifecycle = Any()
    internal var running: ScheduledFuture<*>? = null
        private set
    private var terminal: Runnable? = null
    private var released = false
    internal var nativeOwned: CodeModeSweepOwnership? = null

    /** A sweep changed the records and could not save them yet; the next sweep saves again. */
    private var unsaved = false

    /** Terminal retries save the final state without further TTL mutation, then release hot ownership. */
    fun finish(settle: Runnable) = synchronized(lifecycle) {
        if (released || terminal != null) return@synchronized
        terminal = settle
        sweep()
        monitor.withLock { arm() }
    }

    /** Under [monitor]: starts the sweeps when a record is kept and none run. */
    fun arm() {
        val retained = records.isNotEmpty() || history.entries.isNotEmpty() || terminal != null ||
            nativeOwned?.retained() == true
        if (released) return
        if (running == null && retained) running = CodeModeSweeps.every(interval) { sweep() }
    }

    private fun saveFinal() = monitor.withLock {
        store.save(records, history.entries)
        if (store.release()) {
            records.clear()
            history.entries.clear()
        }
    }

    private fun empty(): Boolean = records.isEmpty() && history.entries.isEmpty() && nativeOwned?.retained() != true

    /** One sweep: what the sweeper changed is saved, a save that fails is logged and made again at the
     *  next sweep, and the sweeps stop once no record or expiry marker is kept. Never throws: a throw would end the
     *  periodic task in silence. */
    internal fun sweep() = synchronized(lifecycle) {
        if (released) return@synchronized
        Cancellables.runCatchingBestEffort {
            unsaved = true
            (terminal ?: save).run()
            if (terminal != null) saveFinal()
            unsaved = false
        }.exceptionOrNull()?.let { failure ->
            config.log(
                "[code-mode] the timed sweep could not save into ${config.state.dir} " +
                    "(${SafeFailureText.render(failure)}); it saves again at the next sweep",
            )
        }
        monitor.withLock {
            val clean = !unsaved && store.settled
            val empty = empty()
            if (empty && clean) {
                running?.cancel(false)
                running = null
                released = terminal != null
            }
        }
    }
}

internal fun interface CodeModeSweepOwnership {
    fun retained(): Boolean
}
