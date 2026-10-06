// NEW: opens and native closes cannot consume the control lanes; lifecycle failures always return bounded diagnostics.
package splice.codemode.host

import kotlinx.coroutines.CancellationException
import splice.codemode.CodeModeFatalFrame
import splice.codemode.CodeModeHeap
import splice.codemode.CodeModeWire
import splice.codemode.HostFrame
import splice.codemode.HostProtocol
import splice.codemode.StreamingCodeModeWire
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// why: metrics and context cancellation must remain live during every bounded native engine open or close.
private const val HOST_CONTROL_THREADS = 2

internal class HostWorkerControls(
    private val output: DataOutputStream,
    private val writes: ReentrantLock,
) : AutoCloseable {
    private val controls = Executors.newFixedThreadPool(HOST_CONTROL_THREADS)
    private val openings = Executors.newFixedThreadPool(CodeModeHeap.maxEnginesPerHost)
    private val closings = Executors.newFixedThreadPool(CodeModeHeap.maxEnginesPerHost)

    fun execute(frame: HostFrame, type: String, command: Runnable) {
        val lane = when (type) {
            "session-open", "start", StreamingCodeModeWire.START -> openings
            "session-close" -> closings
            else -> controls
        }
        lane.execute {
            try {
                command.run()
            } catch (error: CancellationException) {
                failure(frame, CodeModeInfrastructureClass.RUNTIME)
                throw error
            } catch (_: IOException) {
                failure(frame, CodeModeInfrastructureClass.IO)
            } catch (_: RuntimeException) {
                failure(frame, CodeModeInfrastructureClass.RUNTIME)
            }
        }
    }

    fun failed(frame: HostFrame, error: Throwable) {
        val kind = if (error is IOException || error.cause is IOException) {
            CodeModeInfrastructureClass.IO
        } else {
            CodeModeInfrastructureClass.RUNTIME
        }
        failure(frame, kind)
    }

    private fun failure(frame: HostFrame, kind: CodeModeInfrastructureClass) {
        val reply = CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, kind)
        writes.withLock { CodeModeWire.write(output, HostProtocol.reply(frame, reply)) }
    }

    override fun close() {
        val owned = listOf(openings, closings, controls)
        owned.forEach { it.shutdownNow() }
        owned.forEach { it.close() }
    }
}
