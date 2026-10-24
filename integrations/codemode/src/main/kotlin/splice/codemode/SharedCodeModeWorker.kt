// NEW: session-owned engines share only the host transport; parked contexts occupy no executor thread.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import splice.codemode.engine.WorkerSession
import splice.codemode.host.HostWorkerControls
import splice.codemode.wire.CodeModeFrames
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val CONTROL_FRAMES = setOf("session-open", "session-close", "close")

// why: the isolate reports a stack overflow it cannot raise as a RangeError, such as one in its own parse of a long
// member chain, as an exhausted resource with this message; a guest heap past its cap says "Garbage-collected heap
// size exceeded" instead (both measured Oct 23). The overflow is the program's, so it ends as the engine's stack error.
private const val ENGINE_STACK_OVERFLOW = "Resource exhausted: Stack overflow"
private const val GUEST_STACK_ERROR = "RangeError: Maximum call stack size exceeded"

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

internal class HostWorkerDispatcher(
    private val output: DataOutputStream,
    private val sessions: HostWorkerSessions = HostWorkerSessions(),
) : AutoCloseable {
    private val cells = ConcurrentHashMap<Long, HostWorkerCell>()
    private val writes = ReentrantLock()
    private val controls = HostWorkerControls(output, writes)

    fun dispatch(frame: HostFrame) {
        val type = CodeModeFields.requiredString(frame.payload, "type")
        if (type == "session-open") {
            open(frame, type)
        } else if (type in CONTROL_FRAMES) {
            controls.execute(frame, type) { respond(frame, control(frame, type)) }
        } else if (!dispatchGuest(frame, type)) {
            if (type == "start") {
                // Raw-host starts still open their engine, but never wait on another open's lane.
                open(frame, type)
            } else {
                controls.execute(frame, type) { respond(frame, missingCell()) }
            }
        }
    }

    private fun open(frame: HostFrame, type: String) {
        controls.execute(frame, type) {
            sessions.open(frame.session).whenComplete { opened, error ->
                controls.execute(frame, "engines") {
                    if (error != null) {
                        controls.failed(frame, error)
                    } else {
                        opened(frame, type, opened)
                    }
                }
            }
        }
    }

    private fun opened(frame: HostFrame, type: String, accepted: Boolean) {
        if (type == "session-open") {
            respond(frame, if (accepted) HostProtocol.count(sessions.count()) else HostProtocol.capacity())
        } else if (!accepted || !dispatchGuest(frame, type)) {
            respond(frame, missingCell())
        }
    }

    private fun dispatchGuest(frame: HostFrame, type: String): Boolean = sessions.execute(frame.session) {
        val reply = selectCell(frame.cell, frame.session, type)?.reply(frame.payload) ?: missingCell()
        respond(frame, reply)
    }

    private fun control(frame: HostFrame, type: String): JsonObject = when (type) {
        "session-close" -> {
            cells.entries.filter { it.value.owner == frame.session }.forEach { (id, cell) ->
                cells.remove(id)?.close()
            }
            sessions.closeSession(frame.session)
            HostProtocol.count(sessions.count())
        }
        else -> {
            val removed: HostWorkerCell? = cells.remove(frame.cell)
            removed?.close()
            CodeModeWire.completedFrame("", null)
        }
    }

    private fun missingCell(): JsonObject =
        CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO)

    private fun respond(frame: HostFrame, reply: JsonObject) {
        writes.withLock { CodeModeWire.write(output, HostProtocol.reply(frame, reply)) }
    }

    private fun selectCell(id: Long, owner: Long, type: String): HostWorkerCell? = if (type == "start") {
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
            controls.close()
        }
    }
}

private class HostWorkerCell(private val engine: Engine, val owner: Long) : AutoCloseable {
    private val lock = ReentrantLock()
    private val closed = AtomicBoolean()

    @Volatile private var session: WorkerSession? = null

    fun reply(frame: JsonObject): JsonObject = lock.withLock {
        try {
            check(!closed.get()) { "Code-mode cell is closed" }
            answer(frame)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO)
        } catch (_: IllegalArgumentException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.RUNTIME)
        } catch (error: PolyglotException) {
            engineFailure(error)
        } catch (_: RuntimeException) {
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME)
        } catch (failure: VirtualMachineError) {
            // An uncaught error would end the task with no reply and leave the host waiting on this request.
            CodeModeFatalFrame.died(failure)
        }
    }

    /** An exhausted resource ends the program: a stack overflow as the engine's stack error, anything else as its
     *  heap. Any other engine failure is the host's. */
    private fun engineFailure(error: PolyglotException): JsonObject = when {
        !error.isResourceExhausted ->
            CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME)
        error.message == ENGINE_STACK_OVERFLOW -> CodeModeWire.completedFrame("", GUEST_STACK_ERROR)
        else -> CodeModeWire.completedFrame("", "Code-mode guest heap exhausted")
    }

    private fun answer(frame: JsonObject): JsonObject {
        if (CodeModeFields.requiredString(frame, "type") == "results_part") {
            return step(checkNotNull(session).advance(CodeModeFrames.parseResultsPart(frame)))
        }
        return step(execute(frame))
    }

    private fun step(reply: WorkerReply): JsonObject = when {
        reply.waitingForResults -> CodeModeWire.waitingResultsFrame(reply.output, reply.error)
        reply.calls != null -> CodeModeWire.callsFrame(reply.calls, reply.output, reply.error)
        else -> CodeModeWire.completedFrame(checkNotNull(reply.output), reply.error)
    }

    private fun execute(frame: JsonObject): WorkerReply = when (CodeModeFields.requiredString(frame, "type")) {
        "start" -> {
            val current = WorkerSession(engine).also { session = it }
            if (closed.get()) current.close()
            current.start(CodeModeFrames.parseStart(frame))
        }
        else -> checkNotNull(session).advance(CodeModeFrames.parseResults(frame))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session?.close()
    }
}
