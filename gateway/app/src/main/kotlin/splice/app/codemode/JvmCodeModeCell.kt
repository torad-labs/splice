// NEW: serialized stateful callback resumption owns one worker until observed process exit.
package splice.app.codemode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.spi.CodeModeCall
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface ReleaseCodeModeCell {
    operator fun invoke(cell: JvmCodeModeCell)
}

/** Serializes result batches and closes a cell on completion or a failed advance. */
internal class JvmCodeModeCell(
    private val channel: WorkerChannel,
    initial: WorkerReply,
    private val tools: Set<String>,
    private val onClose: ReleaseCodeModeCell,
) : CodeModeCell {
    private val closed: AtomicBoolean = AtomicBoolean()
    private val advanceLock: Mutex = Mutex()
    private var cachedReply: WorkerReply? = initial
    private var pendingCalls: List<CodeModeCall> = initial.calls.orEmpty()
    private var nextId: Int = pendingCalls.size + 1

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = advanceLock.withLock {
        check(!closed.get()) { "Code-mode cell is closed" }
        var advanced = false
        try {
            val reply = cachedReply?.also {
                CodeModeFrames.validateResultSet(emptyList(), results)
                cachedReply = null
            } ?: receiveAfter(results)
            toStep(reply).also { advanced = true }
        } finally {
            if (!advanced) close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            channel.afterExit { onClose(this) }
            channel.close()
        }
    }

    private suspend fun receiveAfter(results: List<CodeModeResult>): WorkerReply {
        check(!closed.get()) { "Code-mode cell is closed" }
        CodeModeFrames.validateResultSet(pendingCalls, results)
        val reply = CodeModeFrames.parseReply(
            frame = channel.exchange(CodeModeWire.resultFrame(results)),
            tools = tools,
            nextId = nextId,
        )
        pendingCalls = reply.calls.orEmpty()
        nextId += pendingCalls.size
        return reply
    }

    private fun toStep(reply: WorkerReply): CodeModeStep = reply.calls?.let { calls ->
        CodeModeStep.Calls(calls)
    } ?: CodeModeStep.Completed(
        output = checkNotNull(reply.output),
        error = reply.error,
    ).also { close() }
}
