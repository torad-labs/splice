// NEW: one shared engine runs independent cell contexts; parked contexts occupy no executor thread.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.graalvm.polyglot.Engine
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object SharedCodeModeWorker {
    fun run(input: DataInputStream, output: DataOutputStream) {
        val configuredEngine = Engine.newBuilder("js")
            .option("engine.SpawnIsolate", "true")
            .option("engine.IsolateOption.MaxHeapSize", "${CodeModeHeap.guestBytes()}")
            .build()
        configuredEngine.use { engine ->
            // Compile and exercise the common launcher before advertising readiness.
            WorkerSession(engine).use { }
            HostWorkerDispatcher(engine, output).use { host ->
                CodeModeWire.write(output, CodeModeWire.readyFrame())
                readFrames(input, host)
            }
        }
    }

    private fun readFrames(input: DataInputStream, host: HostWorkerDispatcher) {
        try {
            while (true) host.dispatch(HostProtocol.parse(CodeModeWire.read(input)))
        } catch (_: IOException) {
            // EOF or a corrupt transport closes the addressed contexts and executor through use.
        }
    }
}

private class HostWorkerDispatcher(
    private val engine: Engine,
    private val output: DataOutputStream,
) : AutoCloseable {
    private val cells = ConcurrentHashMap<Long, HostWorkerCell>()
    private val writes = ReentrantLock()
    private val executor = Executors.newCachedThreadPool()

    fun dispatch(frame: HostFrame) {
        val type = CodeModeFields.requiredString(frame.payload, "type")
        val cell = selectCell(frame.cell, type)
        executor.execute {
            val reply = if (type == "close") {
                val removed: HostWorkerCell? = cells.remove(frame.cell)
                removed?.close()
                CodeModeWire.completedFrame("", null)
            } else {
                cell?.reply(frame.payload)
                    ?: CodeModeFatalFrame.create(
                        CodeModeInfrastructureCategory.PROTOCOL,
                        CodeModeInfrastructureClass.IO,
                    )
            }
            writes.withLock { CodeModeWire.write(output, HostProtocol.frame(frame.cell, frame.request, reply)) }
        }
    }

    private fun selectCell(id: Long, type: String): HostWorkerCell? = if (type == "start") {
        val created = HostWorkerCell(engine)
        if (cells.putIfAbsent(id, created) == null) created else null
    } else {
        cells[id]
    }

    override fun close() {
        try {
            cells.values.forEach(HostWorkerCell::close)
        } finally {
            executor.shutdownNow()
            executor.close()
        }
    }
}

private class HostWorkerCell(private val engine: Engine) : AutoCloseable {
    private val lock = ReentrantLock()
    private val closed = AtomicBoolean()

    @Volatile private var session: WorkerSession? = null

    fun reply(frame: JsonObject): JsonObject = lock.withLock {
        try {
            check(!closed.get()) { "Code-mode cell is closed" }
            val reply = if (CodeModeFields.requiredString(frame, "type") == "start") {
                val start = CodeModeFrames.parseStart(frame)
                val current = WorkerSession(engine).also { session = it }
                if (closed.get()) current.close()
                current.start(start)
            } else {
                checkNotNull(session).advance(CodeModeFrames.parseResults(frame))
            }
            reply.calls?.let(CodeModeWire::callsFrame)
                ?: CodeModeWire.completedFrame(checkNotNull(reply.output), reply.error)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO)
        } catch (_: IllegalArgumentException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.RUNTIME)
        } catch (_: RuntimeException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session?.close()
    }
}
