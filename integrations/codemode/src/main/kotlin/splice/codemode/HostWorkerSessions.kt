// NEW: the engine, isolate, collector and heap belong to a session, never to the host.
package splice.codemode

import org.graalvm.polyglot.Engine
import splice.codemode.engine.WorkerSession
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class HostWorkerSessions : AutoCloseable {
    private val lock = ReentrantLock()
    private val sessions = mutableMapOf<Long, HostWorkerSession>()

    fun open(id: Long): Boolean = lock.withLock {
        if (id in sessions) return true
        if (sessions.size >= CodeModeHeap.maxEnginesPerHost) return false
        val engine = Engine.newBuilder("js")
            .option("engine.SpawnIsolate", "true")
            .option("engine.IsolateOption.MaxHeapSize", "${CodeModeHeap.guestBytes()}")
            .build()
        var warmed = false
        try {
            WorkerSession(engine).use { }
            sessions[id] = HostWorkerSession(engine)
            warmed = true
            true
        } finally {
            if (!warmed) engine.close(true)
        }
    }

    fun engine(id: Long): Engine? = lock.withLock { sessions[id]?.engine }

    fun execute(id: Long, task: Runnable): Boolean = lock.withLock { sessions[id]?.execute(task) == true }

    fun count(): Int = lock.withLock { sessions.values.map { it.engine }.toSet().size }

    fun closeSession(id: Long) {
        val session: HostWorkerSession = lock.withLock { sessions[id] } ?: return
        session.close()
        lock.withLock { sessions.remove(id, session) }
    }

    override fun close() {
        val owned = lock.withLock { sessions.values.toList() }
        owned.forEach(HostWorkerSession::close)
        lock.withLock { sessions.clear() }
    }
}

private class HostWorkerSession(val engine: Engine) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor = Executors.newFixedThreadPool(CodeModeHeap.maxExecutionsPerSession)

    fun execute(task: Runnable): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
            try {
                engine.close(true)
            } finally {
                executor.close()
            }
        }
    }
}
