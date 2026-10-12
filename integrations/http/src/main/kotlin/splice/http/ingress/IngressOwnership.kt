// NEW: each pipelined HTTP request has its own charged lifetime.
package splice.http.ingress

import io.netty.handler.codec.http.HttpRequest
import splice.core.memory.HeapLease
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class IngressOwnership(private val connectionLease: HeapLease?) {
    val halted = AtomicBoolean()
    val draining = AtomicBoolean()
    private val lock = Any()
    private val pending = IdentityHashMap<HttpRequest, IngressRequest>()
    private val responses = ArrayDeque<IngressRequest>()
    private var disconnected = false

    val admitted: Boolean get() = connectionLease != null

    /** Whether no request is on this connection: none read and waiting, none answered late. */
    val idle: Boolean get() = synchronized(lock) { pending.isEmpty() && responses.isEmpty() }
    val stageClose: Boolean get() = synchronized(lock) { halted.get() && responses.isEmpty() }

    fun register(request: HttpRequest, entry: IngressRequest) {
        synchronized(lock) {
            pending[request] = entry
            responses.addLast(entry)
        }
    }

    fun bind(request: HttpRequest): IngressRequest? = synchronized(lock) {
        pending.remove(request)?.also { it.bound = true }
    }

    /** Whether [entry] is the only response this connection still owes, so a reply written now cannot jump an older one. */
    fun soleOutstanding(entry: IngressRequest): Boolean = synchronized(lock) {
        responses.size == 1 && responses.first() === entry
    }

    /**
     * Whether the reply about to go out for the oldest owed request must say Connection: close. It must when that
     * request's body has not all arrived: a body still on its way is never drained, so its connection closes after the
     * reply, and a reply that said keep-alive would send the client's next request into a reset.
     */
    fun announceClose(): Boolean = synchronized(lock) {
        val entry = responses.firstOrNull() ?: return@synchronized false
        if (!entry.arrived) entry.closeAnnounced = true
        entry.closeAnnounced
    }

    fun responded(): Boolean = synchronized(lock) {
        val entry = responses.removeFirstOrNull() ?: return@synchronized false
        entry.responded()
        entry.shouldClose
    }

    fun unread(entry: IngressRequest) {
        synchronized(lock) {
            halted.set(true)
            entry.unread = true
        }
    }

    fun finish(entry: IngressRequest): Boolean = synchronized(lock) {
        entry.finished()
        if (disconnected) entry.responded()
        entry.shouldClose
    }

    fun disconnect() {
        synchronized(lock) {
            disconnected = true
            halted.set(true)
            responses.forEach { it.responded() }
            // An unbound request has no call left to finish it, and a body refused mid-stream was already answered
            // here, so it left the owed responses before any call bound it: finish every one still pending.
            pending.values.forEach { it.finished() }
            pending.clear()
            responses.clear()
            connectionLease?.close()
        }
    }
}

internal class IngressRequest(
    val lease: HeapLease?,
    val refusal: Int? = null,
    val refusalMessage: String? = null,
) {
    var bound = false
    var unread = false

    /** The body's last bytes reached Ktor, so reading it to its end cannot wait on the client. */
    @Volatile
    var arrived = false

    /** Its reply went out saying Connection: close, so the connection closes once that reply is written. */
    @Volatile
    var closeAnnounced = false
    val shouldClose: Boolean get() = (unread || closeAnnounced) && responseSent
    private var appFinished = false
    private var responseSent = false

    fun finished() {
        appFinished = true
        release()
    }

    fun responded() {
        responseSent = true
        release()
    }

    private fun release() {
        if (appFinished && responseSent) lease?.close()
    }
}
