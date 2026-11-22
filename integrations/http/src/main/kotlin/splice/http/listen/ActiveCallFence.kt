// NEW: #525 — tracks in-flight Ktor call coroutines so a server stop cancels them and lets them unwind while
// Netty's event loop is still alive, preventing NettyApplicationResponse.cancel() from submitting to a terminated
// event executor and logging SEVERE RejectedExecutionException stack traces during daemon hand-over.
package splice.http.listen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tracks active Ktor call coroutines and unwinds them ahead of Netty's event-loop shutdown. */
public class ActiveCallFence {
    private val active = ConcurrentHashMap.newKeySet<Job>()

    @Volatile
    private var stopping = false

    /** Arms the fence for a fresh engine start. */
    public fun open() {
        stopping = false
    }

    /** Runs [block] while [job] is registered for shutdown unwinding; rejects new calls once stopping. */
    public suspend inline fun <T> track(job: Job?, block: () -> T): T {
        if (job != null) {
            active.add(job)
            if (stopping) {
                active.remove(job)
                throw CancellationException("server stopping")
            }
        }
        return try {
            block()
        } finally {
            if (job != null) active.remove(job)
        }
    }

    /** Marks the server stopping, cancels every active call coroutine, and waits up to [unwindMs] for them to finish
     *  unwinding while Netty's event loop is still alive. */
    public fun cancelAndAwait(unwindMs: Long) {
        stopping = true
        val pending = active.toList()
        if (pending.isEmpty()) return
        val done = CountDownLatch(pending.size)
        pending.forEach { job ->
            job.invokeOnCompletion { done.countDown() }
            job.cancel(CancellationException("server stopping"))
        }
        try {
            done.await(unwindMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
