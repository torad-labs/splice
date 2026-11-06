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
    private var used = 0L

    @Synchronized
    public fun acquire(bytes: Long): Lease? {
        require(bytes >= 0L)
        if (bytes > LIMIT - used) return null
        used += bytes
        return Lease(bytes)
    }

    @Synchronized
    public fun retainedBytes(): Long = used

    /** Releases a returned page after the consumer has finished with its payload. Cleaner is the fallback. */
    @Synchronized
    public fun <T : Any> release(owner: T) {
        val hash = System.identityHashCode(owner)
        tickets[hash]?.firstOrNull { it.owner.get() === owner }?.cleanable?.clean()
    }

    public class Lease internal constructor(private var bytes: Long) : AutoCloseable, Runnable {
        public fun <T : Any> retain(owner: T, retained: Long) {
            synchronized(PerfWorkBudget) {
                require(retained in 0..bytes)
                used -= bytes - retained
                bytes = retained
            }
            synchronized(PerfWorkBudget) {
                val ticket = Ticket(owner, this)
                ticket.cleanable = cleaner.register(owner, ticket)
                tickets.getOrPut(ticket.hash) { ArrayList() }.add(ticket)
            }
        }

        override fun run(): Unit = close()

        override fun close() {
            synchronized(PerfWorkBudget) {
                used -= bytes
                bytes = 0L
            }
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
