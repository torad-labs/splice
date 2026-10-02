// NEW: session slots are reserved before slow native creation and retained until destruction is confirmed.
package splice.codemode

import kotlinx.coroutines.CancellationException
import org.graalvm.polyglot.Engine
import splice.codemode.engine.HostEngineFactory
import splice.codemode.engine.HostWorkerSession
import splice.codemode.engine.NativeHostEngines
import splice.codemode.engine.WorkerSession
import splice.codemode.host.HostSessionSlot
import splice.codemode.host.HostSessionSlots
import java.io.IOException
import java.util.concurrent.CompletableFuture

internal class HostWorkerSessions(private val factory: HostEngineFactory = NativeHostEngines) : AutoCloseable {
    private val slots = HostSessionSlots()

    fun open(id: Long): CompletableFuture<Boolean> {
        val slot = slots.reserve(id) ?: return CompletableFuture.completedFuture(false)
        if (slot.started.compareAndSet(false, true)) populate(id, slot)
        return slot.opened.thenApply { it != null }
    }

    private fun populate(id: Long, slot: HostSessionSlot): Boolean {
        var created: HostWorkerSession? = null
        try {
            val installed = if (slot.closing) {
                false
            } else {
                val session = HostWorkerSession(factory.create(), factory)
                created = session
                WorkerSession(session.engine).use { }
                slots.publish(slot, session).also { if (!it) session.close() }
            }
            finishOpening(id, slot, created, installed)
            return installed
        } catch (error: CancellationException) {
            failed(id, slot, created, error)
        } catch (error: IOException) {
            failed(id, slot, created, error)
        } catch (ignored: RuntimeException) {
            failed(id, slot, created, ignored)
        }
    }

    private fun finishOpening(id: Long, slot: HostSessionSlot, created: HostWorkerSession?, installed: Boolean) {
        if (!installed) slots.gone(id, slot)
        slot.opened.complete(if (installed) created else null)
    }

    private fun failed(id: Long, slot: HostSessionSlot, created: HostWorkerSession?, error: Exception): Nothing {
        try {
            created?.close()
        } catch (closing: CancellationException) {
            slot.opened.completeExceptionally(closing)
            throw closing
        } catch (closing: IOException) {
            error.addSuppressed(closing)
        } catch (ignored: RuntimeException) {
            error.addSuppressed(ignored)
        } finally {
            if (created == null || created.engineGone) slots.gone(id, slot)
            slot.opened.completeExceptionally(error)
        }
        throw error
    }

    fun engine(id: Long): Engine? = slots.engine(id)

    fun execute(id: Long, task: Runnable): Boolean = slots.execute(id, task)

    fun count(): Int = slots.count()

    fun closeSession(id: Long) {
        val slot = slots.end(id) ?: return
        val session: HostWorkerSession? = slot.opened.join()
        session?.close()
        slots.gone(id, slot)
    }

    override fun close() {
        slots.shutdown().mapNotNull { it.session }.forEach(HostWorkerSession::close)
    }
}
