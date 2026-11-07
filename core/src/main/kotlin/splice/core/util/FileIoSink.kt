package splice.core.util

import splice.core.perf.observation.PerfSinkRejection
import splice.core.perf.observation.PerfSinkRejections
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
    private var snapshotMemory = 0L
    private var serializationSize = 0L
    private var historyMemory = 0L
    private var phaseUnavailable = 0L
    private var writerUnavailable = 0L
    private var asyncEnqueue = 0L
    private var unclassified = 0L
    private var lastRejection: PerfSinkRejection? = null
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
        rejections = if (lastRejection == null) {
            null
        } else {
            PerfSinkRejections(
                snapshotMemory,
                serializationSize,
                historyMemory,
                phaseUnavailable,
                writerUnavailable,
                asyncEnqueue,
                unclassified,
                lastRejection,
            )
        },
    )

    /** Explicitly rejected work still belongs to the sink's observed population. */
    @Synchronized
    public fun reject() {
        observed++
        dropped++
        recordRejection("unclassified")
    }

    /** Rejection labels are a closed set; only byte scalars are retained, never exception text or owner data. */
    @Synchronized
    public fun reject(
        reason: String,
        requestedBytes: Long? = null,
        targetBytes: Long? = null,
        leasedBytes: Long? = null,
    ) {
        observed++
        dropped++
        recordRejection(reason, requestedBytes, targetBytes, leasedBytes)
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
            recordRejection("async_enqueue")
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

    private fun recordRejection(
        reason: String,
        requestedBytes: Long? = null,
        targetBytes: Long? = null,
        leasedBytes: Long? = null,
    ) {
        val classifiedReason = incrementRejection(reason)
        lastRejection = PerfSinkRejection(
            reason = classifiedReason,
            atEpochMs = clock(),
            requestedBytes = requestedBytes,
            targetBytes = targetBytes,
            leasedBytes = leasedBytes,
        )
    }

    private fun incrementRejection(reason: String): String {
        return when (reason) {
            "snapshot_memory" -> {
                snapshotMemory++
                "snapshot_memory"
            }
            "serialization_size" -> {
                serializationSize++
                "serialization_size"
            }
            "history_memory" -> {
                historyMemory++
                "history_memory"
            }
            "phase_unavailable" -> {
                phaseUnavailable++
                "phase_unavailable"
            }
            "writer_unavailable" -> {
                writerUnavailable++
                "writer_unavailable"
            }
            "async_enqueue" -> {
                asyncEnqueue++
                "async_enqueue"
            }
            else -> {
                unclassified++
                "unclassified"
            }
        }
    }
}
