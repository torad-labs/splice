package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfHistory
import splice.core.perf.observation.PerfHistoryRead
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.AsyncFileIo
import splice.core.util.FileIoSink
import splice.core.util.PerfWorkBudget
import splice.core.util.PerfWorkPhase
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Path

/** Durable minute replacements and retained hourly history, usable without a running daemon. */
public class PerfAggregateStore(directory: Path, private val identity: PerfIdentity, private val clock: WallClock) :
    PerfHistory, AutoCloseable {
    private val files = PerfHistoryFiles(directory)
    private val writer = PerfHistoryWriter(directory)
    private val sink = FileIoSink(HISTORY_SINK, clock)
    private val issues = LinkedHashMap<String, PerfIssue>()
    private val retention = PerfHistoryRetention(files)
    public val costs: PerfSourceCosts = PerfSourceCosts()
    private val reads = PerfHistoryReader(files, costs)
    private var closed = false
    private var durableUntil = 0L
    private var writable = false
    private var previousIdentity: PerfIdentity? = null

    init {
        if (writer.uncertain()) issue(PerfIssue("checkpoint_uncertain", HISTORY_SINK))
        try {
            files.readCheckpoint()?.let { saved ->
                durableUntil = saved.durableUntilMs
                previousIdentity = saved.identity
                saved.issues.forEach(::issue)
                if (!saved.clean) {
                    issue(
                        PerfIssue("checkpoint_uncertain", HISTORY_SINK, detail = "after ${saved.durableUntilMs}"),
                    )
                }
            }
        } catch (_: IOException) {
            issue(PerfIssue("checkpoint_read_error", HISTORY_SINK))
        } catch (_: IllegalArgumentException) {
            issue(PerfIssue("checkpoint_malformed", HISTORY_SINK))
        }
    }

    /** Call once at daemon startup. Offline readers never acquire or dirty the writable session. */
    @Synchronized
    public fun openWriter(): Boolean {
        if (closed) return false
        if (writable) return true
        writable = try {
            require(PerfHistorySize.identity(identity) <= MAX_IDENTITY_BYTES) { "history identity size limit" }
            writer.open(clock())
            previousIdentity?.let { saved ->
                if (saved.bootId != identity.bootId) issue(PerfIssue("boot_changed", HISTORY_SINK))
                if (saved.build != identity.build) issue(PerfIssue("build_changed", HISTORY_SINK))
                if (saved.configRevision != identity.configRevision) issue(PerfIssue("config_changed", HISTORY_SINK))
            }
            true
        } catch (_: IOException) {
            issue(PerfIssue("writer_open_failed", HISTORY_SINK))
            false
        } catch (_: IllegalArgumentException) {
            issue(PerfIssue("writer_identity_limit", HISTORY_SINK))
            false
        }
        return writable
    }

    @Synchronized
    override fun append(snapshot: PerfAggregateSnapshot): Boolean = append(snapshot, null)

    private fun append(snapshot: PerfAggregateSnapshot, snapshotLease: PerfWorkBudget.Lease?): Boolean {
        val phase = PerfWorkPhase.tryCheckpoint() ?: return rejectCheckpoint()
        return phase.use { append(snapshot, snapshotLease, it) }
    }

    /** The producer already awaits the phase before allocating its detached snapshot. */
    @Synchronized
    internal fun appendOwned(
        snapshot: PerfAggregateSnapshot,
        lease: PerfWorkBudget.Lease,
        phase: PerfWorkPhase.Lease,
    ): Boolean = append(snapshot, lease, phase)

    private fun append(
        snapshot: PerfAggregateSnapshot,
        snapshotLease: PerfWorkBudget.Lease?,
        phase: PerfWorkPhase.Lease,
    ): Boolean {
        if (!openWriter()) {
            sink.reject()
            return false
        }
        val charge = PerfHistoryFormat.estimate(snapshot)
        // openWriter already rejected a closed owner while this append holds the store monitor.
        val lease = if (charge <= MAX_PENDING_BYTES) {
            if (snapshotLease == null) {
                PerfWorkBudget.acquire(charge + PERF_HISTORY_WORK_BYTES)
            } else {
                snapshotLease.transfer(charge + PERF_HISTORY_WORK_BYTES)
            }
        } else {
            null
        }
        if (lease == null) return rejectCheckpoint()
        var writing = phase
        var accepted = false
        try {
            writing = phase.transferCheckpoint()
            val transferred = writing
            val frozen = if (snapshotLease == null) PerfHistoryFormat.frozen(snapshot) else snapshot
            accepted = AsyncFileIo.submitFor(files.checkpoint, sink) {
                transferred.use { lease.use { persist(frozen) } }
            }
            return accepted
        } finally {
            if (!accepted) {
                lease.close()
                writing.close()
            }
        }
    }

    @Synchronized
    internal fun rejectCheckpoint(): Boolean {
        sink.reject()
        issue(PerfIssue("checkpoint_dropped", HISTORY_SINK))
        return false
    }

    override fun read(fromMs: Long, untilMs: Long): PerfHistoryRead {
        require(fromMs >= 0 && untilMs > fromMs) { "history interval must be positive and ordered" }
        return reads.read(fromMs, untilMs, clock(), evidence(), sink.status())
    }

    override fun status(): PerfSinkStatus = sink.status()

    override fun close() {
        synchronized(this) { closed = true }
        if (!writable) return
        try {
            val settled = AsyncFileIo.awaitFile(files.checkpoint)
            val status = sink.status()
            if (settled && status.failed + status.dropped == 0L) {
                files.lock().use {
                    files.checkpoint(PerfHistoryCheckpoint(identity, durableUntil, true, evidence()))
                    writer.clean()
                }
            }
        } catch (_: IOException) {
            issue(PerfIssue("close_checkpoint_failed", HISTORY_SINK))
        } finally {
            writer.close()
        }
    }

    private fun persist(snapshot: PerfAggregateSnapshot): Boolean = try {
        files.lock().use {
            retention.maintain(clock()).forEach(::issue)
            val cutoff = PerfHistoryFormat.hour(clock() - PERF_DAY_MS)
            snapshot.points.groupBy { PerfHistoryFormat.minute(it.fromMs) }.forEach { (minute, points) ->
                writeRetained(snapshot, minute, points, cutoff)
            }
            if (snapshot.untilMs < durableUntil) issue(PerfIssue("clock_or_checkpoint_reordered", HISTORY_SINK))
            durableUntil = maxOf(durableUntil, snapshot.untilMs)
            retention.maintain(clock()).forEach(::issue)
            files.checkpoint(PerfHistoryCheckpoint(identity, durableUntil, issues = evidence()))
            costs.disk(files.inventory().sumOf { it.bytes })
        }
        true
    } catch (_: IOException) {
        issue(PerfIssue("checkpoint_write_error", HISTORY_SINK))
        false
    } catch (_: IllegalArgumentException) {
        issue(PerfIssue("checkpoint_malformed", HISTORY_SINK))
        false
    }

    private fun writeRetained(
        snapshot: PerfAggregateSnapshot,
        minute: Long,
        points: List<PerfAggregatePoint>,
        cutoff: Long,
    ) {
        if (minute < cutoff || files.sealedHour(minute)) {
            issue(PerfIssue("late_checkpoint_expired", HISTORY_SINK))
        } else {
            writeMinute(snapshot, minute, points)
        }
    }

    private fun writeMinute(snapshot: PerfAggregateSnapshot, minute: Long, points: List<PerfAggregatePoint>) {
        val until = minOf(minute + PERF_MINUTE_MS, snapshot.untilMs)
        val current = files.existing(minute, PERF_MINUTE_MS)
        val revisions = current?.revisions.orEmpty().toMutableMap()
        val identityKey = snapshot.identity.toString()
        if (snapshot.untilMs < (revisions[identityKey] ?: Long.MIN_VALUE)) return
        val previous = current?.snapshots.orEmpty().associateBy { it.identity }.toMutableMap()
        val part = snapshot.copy(
            fromMs = minute,
            untilMs = until,
            points = points,
            coverage = snapshot.coverage.copy(
                requestedFromMs = minute,
                requestedUntilMs = until,
                observedMs = minOf(snapshot.coverage.observedMs, (until - minute).coerceAtLeast(0L)),
            ),
        )
        val merged = PerfHistoryFormat.merge(previous[snapshot.identity], part)
        if (merged == previous[snapshot.identity]) return
        previous[snapshot.identity] = merged
        revisions[identityKey] = snapshot.untilMs
        retention.reserve(clock()).forEach(::issue)
        files.write(
            PerfHistoryFile(
                resolutionMs = PERF_MINUTE_MS,
                fromMs = minute,
                snapshots = previous.values.toList(),
                revisions = revisions,
            ),
        )
    }

    @Synchronized
    private fun issue(value: PerfIssue) {
        val old = issues[value.code]
        if (old != null || issues.size < MAX_STORE_ISSUES) {
            issues[value.code] = value.copy(
                count = (old?.count ?: 0L) + value.count,
            )
        }
    }

    @Synchronized
    private fun evidence(): List<PerfIssue> = issues.values.toList()
}

// Admit at most 4 MiB for a pending history snapshot so asynchronous persistence cannot retain arbitrary producer state.
private const val MAX_PENDING_BYTES = 4L * 1_024 * 1_024

// Retain sixty-four distinct store issue codes; repeated occurrences update their existing counts.
private const val MAX_STORE_ISSUES = 64

// Reject identities above a 16 KiB charge before creating a durable writer session.
private const val MAX_IDENTITY_BYTES = 16L * 1_024
