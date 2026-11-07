package splice.core.util

import java.lang.ref.Cleaner
import java.lang.ref.WeakReference

/** One process-wide allowance shared by history checkpoints, reads and cursor state. */
public object PerfWorkBudget {
    // why: Reserve 16 MiB for query/checkpoint/cursor work within the plan's combined 32 MiB instrumentation allowance.
    public const val LIMIT_BYTES: Long = 16L * 1_024 * 1_024
    private const val LIMIT = LIMIT_BYTES
    private val cleaner = Cleaner.create()
    private val tickets = HashMap<Int, MutableList<Ticket<*>>>()
    private val reservations = LongArray(Kind.entries.size)
    private val retainedTickets = LongArray(Kind.entries.size)
    private val lastRefusal = RefusalRecord()
    private var used = 0L

    @Synchronized
    public fun acquire(bytes: Long): Lease? = acquire(bytes, Kind.OTHER)

    /** Adds a fixed diagnostic label without changing admission or the caller's lease lifetime. */
    @Synchronized
    public fun acquire(bytes: Long, kind: Kind): Lease? {
        require(bytes >= 0L)
        if (bytes > LIMIT - used) {
            lastRefusal.capture(bytes, kind)
            return null
        }
        used += bytes
        reservations[kind.ordinal] += bytes
        return Lease(bytes, kind)
    }

    /** Captures this admission's refusal under the budget lock for callers that expose causal diagnostics. */
    @Synchronized
    public fun acquireWithOutcome(bytes: Long, kind: Kind = Kind.OTHER): Admission {
        require(bytes >= 0L)
        if (bytes > LIMIT - used) {
            lastRefusal.capture(bytes, kind)
            return Admission(null, lastRefusal.snapshot())
        }
        used += bytes
        reservations[kind.ordinal] += bytes
        return Admission(Lease(bytes, kind), null)
    }

    @Synchronized
    public fun retainedBytes(): Long = used

    /** Fixed-size accounting only: no reservation, ticket scan, reachability check or heap measurement. */
    @Synchronized
    public fun snapshot(): Snapshot = Snapshot(accounting(used, reservations, retainedTickets), lastRefusal.snapshot())

    private fun accounting(total: Long, reserved: LongArray, retained: LongArray): Accounting = Accounting(
        total,
        reserved.sum(),
        retained.sum(),
        Kind.entries.map { KindBytes(it, reserved[it.ordinal], retained[it.ordinal]) },
    )

    /** Releases a returned page after the consumer has finished with its payload. Cleaner is the fallback. */
    @Synchronized
    public fun <T : Any> release(owner: T) {
        val hash = System.identityHashCode(owner)
        tickets[hash]?.firstOrNull { it.owner.get() === owner }?.cleanable?.clean()
    }

    public class Lease @JvmOverloads internal constructor(
        private var bytes: Long,
        private val kind: Kind = Kind.OTHER,
    ) : AutoCloseable, Runnable {
        private var ticketed = false
        private var closed = false

        /** Atomically replaces this reservation. Refusal leaves it owned; success invalidates this lease. */
        public fun transfer(totalBytes: Long): Lease? = synchronized(PerfWorkBudget) {
            transferLease(totalBytes)
        }

        /** Captures this transfer's marginal refusal and accounting under the same budget lock. */
        public fun transferWithOutcome(totalBytes: Long): Admission = synchronized(PerfWorkBudget) {
            val replacement = transferLease(totalBytes)
            Admission(replacement, if (replacement == null) lastRefusal.snapshot() else null)
        }

        private fun transferLease(totalBytes: Long): Lease? {
            require(totalBytes >= 0L)
            check(!closed && !ticketed) { "Only an open, unretained reservation can transfer" }
            val additional = totalBytes - bytes
            if (additional > LIMIT - used) {
                lastRefusal.capture(additional, kind)
                return null
            }
            val replacement = Lease(totalBytes, kind)
            used += additional
            reservations[kind.ordinal] += additional
            bytes = 0L
            closed = true
            return replacement
        }

        public fun <T : Any> retain(owner: T, retained: Long) {
            synchronized(PerfWorkBudget) {
                check(!closed) { "A closed reservation cannot retain an owner" }
                require(retained in 0..bytes)
                used -= bytes - retained
                charged()[kind.ordinal] -= bytes - retained
                bytes = retained
                val ticket = Ticket(owner, this)
                ticket.cleanable = cleaner.register(owner, ticket)
                tickets.getOrPut(ticket.hash) { ArrayList() }.add(ticket)
                if (!ticketed) {
                    reservations[kind.ordinal] -= bytes
                    retainedTickets[kind.ordinal] += bytes
                    ticketed = true
                }
            }
        }

        override fun run(): Unit = close()

        override fun close() {
            synchronized(PerfWorkBudget) {
                used -= bytes
                charged()[kind.ordinal] -= bytes
                bytes = 0L
                closed = true
            }
        }

        private fun charged(): LongArray = if (ticketed) retainedTickets else reservations
    }

    /** Labels are explicit groups of lease acquisition sites, never owner identities. */
    public enum class Kind(public val code: String) {
        QUERY_REPLY("query_reply"),
        QUERY_CACHE("query_cache"),
        HISTORY_READ("history_read"),
        EVIDENCE_READ("evidence_read"),
        CONFIGURATION("configuration"),
        RUNTIME_SAMPLE("runtime_sample"),
        EVIDENCE_COVERAGE("evidence_coverage"),
        WEBHOOK_QUEUE("webhook_queue"),
        OTHER("other"),
    }

    /** Reservations may be long-lived; retained tickets may include collected owners awaiting Cleaner. */
    public data class KindBytes(
        val kind: Kind,
        val unretainedReservationBytes: Long,
        val retainedTicketBytes: Long,
    )

    /** Actual charged bytes. Neither phase establishes logical owner liveness or measured heap size. */
    public data class Accounting(
        val leasedBytes: Long,
        val unretainedReservationBytes: Long,
        val retainedTicketBytes: Long,
        val kinds: List<KindBytes>,
    )

    /** The latest process-wide refusal; later failures replace it and successful acquisitions do not clear it. */
    public data class Refusal(val requestedBytes: Long, val requestedKind: Kind, val accounting: Accounting)

    /** Either the acquired owner or the refusal captured atomically with the failed admission. */
    public data class Admission(val lease: Lease?, val refusal: Refusal?)

    public data class Snapshot(val accounting: Accounting, val lastRefusal: Refusal?)

    private class RefusalRecord {
        private var requestedBytes = 0L
        private var requestedKind: Kind? = null
        private var total = 0L
        private val reserved = LongArray(Kind.entries.size)
        private val retained = LongArray(Kind.entries.size)

        fun capture(bytes: Long, kind: Kind) {
            requestedBytes = bytes
            requestedKind = kind
            total = used
            reservations.copyInto(reserved)
            retainedTickets.copyInto(retained)
        }

        fun snapshot(): Refusal? = requestedKind?.let {
            Refusal(
                requestedBytes,
                it,
                accounting(total, reserved, retained),
            )
        }
    }

    private class Ticket<T : Any>(value: T, private val lease: Lease) : Runnable {
        val owner = WeakReference(value)
        val hash = System.identityHashCode(value)
        var cleanable: Cleaner.Cleanable? = null

        override fun run() {
            lease.close()
            synchronized(PerfWorkBudget) {
                tickets[hash]?.let { list ->
                    list.remove(this)
                    if (list.isEmpty()) tickets.remove(hash)
                }
            }
        }
    }
}
