package splice.codemode.nativehost

import java.util.concurrent.atomic.AtomicBoolean

/** A preemption requested during dispatch remains latched until its wait can receive it. */
internal class NativeControlObservation(val operation: NativeOperation) {
    private val requested = AtomicBoolean()
    private val sent = AtomicBoolean()

    fun requestYield() {
        requested.set(true)
    }

    fun claimYield(): Boolean = requested.get() && operation.wasDispatched() && sent.compareAndSet(false, true)
}

/** A logical wait can be preempted while it is reopening its physical session. */
internal class NativeControlPreemption {
    private val signal = AtomicBoolean()
    val requested: Boolean get() = signal.get()

    fun request() {
        signal.set(true)
    }
}
