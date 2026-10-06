// NEW: a finite read quota refuses reservations and has no availability flow for an admission waiter.
package splice.core.memory

/** Closing ends new admission, not the charges retained by escaped lease owners. */
public class HeapReadView internal constructor(private val ledger: HeapLedger) : HeapReservations(), AutoCloseable {
    override val limitBytes: Long get() = ledger.limitBytes

    override fun reserve(bytes: Long): HeapLease? = ledger.reserve(bytes)

    override fun readShare(): HeapReadView = HeapReadView(ledger.readShare())

    override fun close() {
        ledger.close()
    }
}
