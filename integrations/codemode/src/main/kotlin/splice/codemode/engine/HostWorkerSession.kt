// NEW: a session owns bounded execution stacks until native engine destruction is confirmed.
package splice.codemode.engine

import org.graalvm.polyglot.Engine
import splice.codemode.CodeModeHeap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface HostEngineFactory {
    fun create(): Engine
    fun close(engine: Engine)
}

internal object NativeHostEngines : HostEngineFactory {
    override fun create(): Engine = Engine.newBuilder("js")
        .option("engine.SpawnIsolate", "true")
        .option("engine.IsolateOption.MaxHeapSize", "${CodeModeHeap.guestBytes()}")
        .build()

    override fun close(engine: Engine) {
        engine.close(true)
    }
}

internal class HostWorkerSession(val engine: Engine, private val factory: HostEngineFactory) : AutoCloseable {
    private val stopped = AtomicBoolean()
    private val lifecycle = ReentrantLock()
    private val executor = Executors.newFixedThreadPool(CodeModeHeap.maxExecutionsPerSession)

    @Volatile var engineGone = false
        private set

    fun execute(task: Runnable): Boolean {
        if (stopped.get()) return false
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        lifecycle.withLock {
            if (engineGone) return
            stopped.set(true)
            executor.shutdownNow()
            factory.close(engine)
            engineGone = true
            executor.close()
        }
    }
}
