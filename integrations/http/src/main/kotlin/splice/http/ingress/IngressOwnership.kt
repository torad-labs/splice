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
            responses.forEach {
                it.responded()
                if (!it.bound) it.finished()
            }
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
    val shouldClose: Boolean get() = unread && responseSent
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
