package splice.upstream.retry

import splice.upstream.retry.gate.GateWaiter

/** Every gate drain uses the same hand-off and undelivered-permit compensation. */
internal object GateResumptions {
    fun resume(waiters: List<GateWaiter>, gate: InflightGate) {
        for (waiter in waiters) {
            val continuation = waiter.continuation ?: continue
            continuation.resume(true) { _, _, _ -> gate.returnUndelivered(waiter.borrowed) }
        }
    }
}
