package splice.upstream.retry.gate

import splice.upstream.retry.LiveLimit

/** Admission and queue ceilings are read fresh for each decision while the gate owns its state lock. */
internal class InflightCapacity(private val inflight: LiveLimit, private val queued: LiveLimit) {
    fun admits(count: Int): Boolean = inflight().let { it <= 0 || count < it }

    fun queues(count: Int): Boolean = queued().let { it <= 0 || count < it }
}
