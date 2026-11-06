package splice.app.observability

import splice.core.config.ConfigService
import splice.core.config.EffectiveConfigObservation
import splice.core.config.EffectiveConfigObserver
import splice.core.util.PerfWorkBudget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Configuration publication only copies bounded scalar maps; hashing and persistence run on the owned worker. */
internal class PerfConfigTracking(
    private val config: ConfigService,
    private val heads: Set<String>,
    private val boot: String,
    val history: PerfConfigHistory,
) : AutoCloseable {
    private val originalsLease = PerfWorkBudget.acquire(heads.size.toLong() * BYTES_PER_HEAD)
    private val originals = if (originalsLease == null) {
        emptyMap()
    } else {
        heads.associateWith {
            PerfConfigRevision.project(config.getConfig(it).asMap())
        }
    }
    private val queue = ArrayBlockingQueue<Change>(MAX_CHANGES)
    private val lostAt = AtomicLong()
    var afterChange = PerfConfigSignal {}

    fun start() {
        config.effectiveObserver = EffectiveConfigObserver(::changed)
        drain()
    }

    private fun changed(observation: EffectiveConfigObservation) {
        val bytes = heads.size.toLong() * BYTES_PER_HEAD
        val lease = PerfWorkBudget.acquire(bytes)
        if (listOf(lease == null, originalsLease == null, queue.remainingCapacity() == 0).any { it }) {
            lease?.close()
            lostAt.compareAndSet(0, observation.atMs)
        } else {
            val values = heads.associateWith { head ->
                PerfConfigRevision.project(
                    PerfConfigRevision.effective(
                        originals.getValue(head),
                        observation.heads[head] ?: observation.global,
                    ),
                )
            }
            if (!queue.offer(Change(observation.atMs, values, requireNotNull(lease), observation.unknownFromMs))) {
                lease.close()
                lostAt.compareAndSet(0, observation.atMs)
            }
        }
        afterChange.changed()
    }

    @Synchronized
    fun drain() {
        val lost = lostAt.getAndSet(0)
        while (true) {
            val change = queue.poll() ?: break
            change.lease.use {
                if (lost == 0L) apply(change)
            }
        }
        if (lost != 0L) heads.forEach { history.changed(it, lost, null, boot) }
    }

    private fun apply(change: Change) {
        change.values.forEach { (head, values) ->
            change.unknownFromMs?.let { history.changed(head, it, null, boot) }
            history.changed(head, change.at, PerfConfigRevision.of(values), boot)
        }
    }

    override fun close() {
        config.effectiveObserver = null
        drain()
        originalsLease?.close()
        history.close()
    }

    private data class Change(
        val at: Long,
        val values: Map<String, Map<String, Any?>>,
        val lease: PerfWorkBudget.Lease,
        val unknownFromMs: Long?,
    )
}

internal fun interface PerfConfigSignal {
    fun changed()
}

private const val MAX_CHANGES = 8
private const val BYTES_PER_HEAD = 32L * 1_024
