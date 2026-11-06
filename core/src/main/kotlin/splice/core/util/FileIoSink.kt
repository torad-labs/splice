package splice.core.util

import splice.core.perf.observation.PerfSinkStatus
import java.io.IOException
import java.nio.file.Path

/** A write returns true only after its persistence operation actually succeeds. */
public fun interface FileIoWrite {
    public fun write(): Boolean
}

/** Per-owner accounting; taking a snapshot performs no writes and emits no observations. */
public class FileIoSink(private val name: String, private val clock: WallClock) {
    private var observed = 0L
    private var enqueued = 0L
    private var written = 0L
    private var failed = 0L
    private var dropped = 0L
    private var lastSuccess: Long? = null
    private val pending = LinkedHashMap<Long, Long>()

    @Synchronized
    public fun status(): PerfSinkStatus = PerfSinkStatus(
        sink = name,
        observed = observed,
        enqueued = enqueued,
        written = written,
        failed = failed,
        dropped = dropped,
        pending = pending.size.toLong(),
        oldestPendingMs = pending.values.minOrNull()?.let { (MonoClock.nowMs() - it).coerceAtLeast(0L) },
        lastSuccessEpochMs = lastSuccess,
    )

    /** Explicitly rejected work still belongs to the sink's observed population. */
    @Synchronized
    public fun reject() {
        observed++
        dropped++
    }

    @Synchronized
    internal fun submit(file: Path, task: FileIoWrite): Boolean {
        val ticket = ++observed
        enqueued++
        pending[ticket] = MonoClock.nowMs()
        val accepted = AsyncFileIo.submitFor(file) {
            var success = false
            try {
                success = task.write()
                if (!success) {
                    AsyncFileIo.reportWriteFailure()
                    throw IOException("named file sink write failed")
                }
            } finally {
                settled(ticket, success)
            }
        }
        if (!accepted) {
            pending.remove(ticket)
            enqueued--
            dropped++
        }
        return accepted
    }

    @Synchronized
    private fun settled(ticket: Long, success: Boolean) {
        pending.remove(ticket)
        if (success) {
            written++
            lastSuccess = clock()
        } else {
            failed++
        }
    }
}
