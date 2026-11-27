package splice.app.mods.handover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mods.sessions.ModSessionEntry

// why: new processes must also boot if the predecessor was killed or did not support this protocol.
private const val CONFIRMATION_MS = 15_000L

internal class ModHandoverStarts(private val expected: Boolean, private val predecessor: Long? = null) {
    private val received = mutableListOf<ModSessionEntry>()
    private val confirmed = CompletableDeferred<List<ModSessionEntry>>()
    private val waitingForPrefix: Boolean get() = expected && !confirmed.isCompleted

    @Synchronized
    fun accept(batch: ModHandoverBatch) {
        if (!waitingForPrefix || !preamble(batch)) return
        received.addAll(batch.starts)
        if (batch.startsComplete) confirmed.complete(received.toList())
    }

    /** Until the predecessor's last transfer batch arrives (it carries what that daemon already accepted), another
     *  sender's batch waits: the sender retries, and a batch is never told before it is known to be new. */
    @Synchronized
    fun holds(batch: ModHandoverBatch): Boolean =
        waitingForPrefix && !preamble(batch)

    private fun preamble(batch: ModHandoverBatch): Boolean {
        if (batch.starts.isEmpty() && !batch.startsComplete) return false
        // The launcher's predecessor sends our receipts. Earlier daemons may still be retrying on the same port.
        // A legacy sender has no PID prefix; its direct successor still understands its session-start transfer.
        val sender = batch.sender?.substringBefore('/')?.toLongOrNull()
        return predecessor == null || sender == null || sender == predecessor
    }

    /** A predecessor that never sends its last batch stops holding the other senders when the wait ends. */
    suspend fun await(): List<ModSessionEntry> {
        if (!expected) return emptyList()
        return withTimeoutOrNull(CONFIRMATION_MS) { confirmed.await() } ?: expire()
    }

    @Synchronized
    private fun expire(): List<ModSessionEntry> = received.toList().also { confirmed.complete(it) }
}
