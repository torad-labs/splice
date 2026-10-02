// NEW: session-owned engines share only the host transport; parked contexts occupy no executor thread.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import splice.codemode.engine.WorkerSession
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

// why: control acknowledgments and cancellation must remain live when every bounded guest lane is executing.
private const val CONTROL_THREADS = 2
private val CONTROL_FRAMES = setOf("session-open", "session-close", "engines", "close")

internal object SharedCodeModeWorker {
    fun run(input: DataInputStream, output: DataOutputStream) {
        HostWorkerDispatcher(output).use { host ->
            // Hosts are cold. Only a session's first cell opens and warms its own isolate.
            CodeModeWire.write(output, CodeModeWire.readyFrame())
            readFrames(input, host)
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
    private val output: DataOutputStream,
) : AutoCloseable {
    private val cells = ConcurrentHashMap<Long, HostWorkerCell>()
    private val sessions = HostWorkerSessions()
    private val writes = ReentrantLock()
    private val controls = Executors.newFixedThreadPool(CONTROL_THREADS)

    fun dispatch(frame: HostFrame) {
        val type = CodeModeFields.requiredString(frame.payload, "type")
        if (type in CONTROL_FRAMES) {
            controls.execute { respond(frame, control(frame, type)) }
        } else if (!dispatchGuest(frame, type)) {
            controls.execute {
                // Legacy raw-host starts have no preceding session-open. Warm them on control, never run guest code there.
                val starting = type == "start" || type == StreamingCodeModeWire.START
                val opened = starting && sessions.open(frame.session)
                if (!opened || !dispatchGuest(frame, type)) respond(frame, missingCell())
            }
        }
    }

    private fun dispatchGuest(frame: HostFrame, type: String): Boolean = sessions.execute(frame.session) {
        val reply = selectCell(frame.cell, frame.session, type)?.reply(frame.payload) ?: missingCell()
        respond(frame, reply)
    }

    private fun control(frame: HostFrame, type: String): JsonObject = when (type) {
        "session-open" -> if (sessions.open(frame.session)) {
            HostProtocol.count(sessions.count())
        } else {
            HostProtocol.command("capacity")
        }
        "session-close" -> {
            cells.entries.filter { it.value.owner == frame.session }.forEach { (id, cell) ->
                cells.remove(id)?.close()
            }
            sessions.closeSession(frame.session)
            HostProtocol.count(sessions.count())
        }
        "engines" -> HostProtocol.count(sessions.count(), cells.values.count(HostWorkerCell::isRunning))
        else -> {
            val removed: HostWorkerCell? = cells.remove(frame.cell)
            removed?.close()
            CodeModeWire.completedFrame("", null)
        }
    }

    private fun missingCell(): JsonObject =
        CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO)

    private fun respond(frame: HostFrame, reply: JsonObject) {
        writes.withLock { CodeModeWire.write(output, HostProtocol.frame(frame.cell, frame.request, reply)) }
    }

    private fun selectCell(id: Long, owner: Long, type: String): HostWorkerCell? = if (
        type == "start" || type == StreamingCodeModeWire.START
    ) {
        val engine = sessions.engine(owner) ?: return null
        val created = HostWorkerCell(engine, owner)
        if (cells.putIfAbsent(id, created) == null) created else null
    } else {
        cells[id]
    }

    override fun close() {
        try {
            cells.values.forEach(HostWorkerCell::close)
        } finally {
            sessions.close()
            controls.shutdownNow()
            controls.close()
        }
    }
}

private class HostWorkerCell(private val engine: Engine, val owner: Long) : AutoCloseable {
    private val lock = ReentrantLock()
    private val closed = AtomicBoolean()

    @Volatile private var session: WorkerSession? = null

    @Volatile var isRunning: Boolean = false
        private set

    fun reply(frame: JsonObject): JsonObject = lock.withLock {
        try {
            check(!closed.get()) { "Code-mode cell is closed" }
            isRunning = true
            step(execute(frame))
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO)
        } catch (_: IllegalArgumentException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.RUNTIME)
        } catch (error: PolyglotException) {
            if (error.isResourceExhausted) {
                CodeModeWire.completedFrame("", "Code-mode guest heap exhausted")
            } else {
                CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME)
            }
        } catch (_: RuntimeException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME)
        } finally {
            isRunning = false
        }
    }

    private fun step(reply: WorkerReply): JsonObject = when {
        reply.waitingForInput -> StreamingCodeModeWire.waitingFrame()
        reply.calls != null -> CodeModeWire.callsFrame(reply.calls)
        else -> CodeModeWire.completedFrame(checkNotNull(reply.output), reply.error)
    }

    private fun execute(frame: JsonObject): WorkerReply = when (CodeModeFields.requiredString(frame, "type")) {
        "start", StreamingCodeModeWire.START -> {
            val current = WorkerSession(engine).also { session = it }
            if (closed.get()) current.close()
            current.start(CodeModeFrames.parseStart(frame))
        }
        StreamingCodeModeWire.SOURCE_INPUT_FRAME -> checkNotNull(session).input(frame)
        else -> checkNotNull(session).advance(CodeModeFrames.parseResults(frame))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session?.close()
    }
}
