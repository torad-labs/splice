package splice.upstream.retry.gate

import kotlinx.coroutines.CancellableContinuation
import splice.upstream.retry.InflightGate

/** One queued request is a reference identity, including its exact cancellation and borrowed-permit ownership. */
internal class GateWaiter(
    var resumed: Boolean = false,
    var continuation: CancellableContinuation<Boolean>? = null,
    var queuedAt: Long = 0L,
    val session: String? = null,
    var borrowed: InflightGate.Slot? = null,
) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    fun turn(admitted: Boolean, immediate: Boolean, now: Long): GateAdmission = when {
        !admitted -> GateAdmission.Refused
        borrowed != null -> GateAdmission.Borrowed(checkNotNull(borrowed))
        immediate -> GateAdmission.Immediate
        else -> GateAdmission.Queued(now - queuedAt)
    }
}

/** The delivery decision retains whether the permit was newly counted, queued, or borrowed from a source. */
internal sealed class GateAdmission {
    data object Refused : GateAdmission()
    data object Immediate : GateAdmission()
    data class Queued(val waitedMs: Long) : GateAdmission()
    data class Borrowed(val slot: InflightGate.Slot) : GateAdmission()

    fun answer(gate: InflightGate): InflightGate.Admission = when (this) {
        Refused -> InflightGate.Admission.AtCapacity
        Immediate -> InflightGate.Admission.Acquired(gate.deliver(null))
        is Queued -> InflightGate.Admission.Acquired(gate.deliver(waitedMs))
        is Borrowed -> InflightGate.Admission.Acquired(slot)
    }
}
