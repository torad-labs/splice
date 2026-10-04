// NEW: serialized stateful callback resumption owns one addressed context until its close acknowledgement.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeSourcePersistenceException
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.atomic.AtomicReference

internal fun interface ReleaseCodeModeCell {
    operator fun invoke(cell: JvmCodeModeCell)
}

/** Serializes result batches and closes a cell on completion or a failed advance. */
internal class JvmCodeModeCell(
    private val channel: CellChannel,
    initial: WorkerReply,
    private val tools: Set<String>,
    private val onClose: ReleaseCodeModeCell,
    private val source: CodeModeSource? = null,
) : CodeModeCell {
    private enum class End { CLOSED, STOPPED }

    // The first terminal cause wins, including a transport exit arriving after a local close.
    private val end = AtomicReference<End?>()
    private val lost = CompletableDeferred<Unit>()
    private val advanceLock: Mutex = Mutex()
    private var cachedReply: WorkerReply? = initial
    private var pendingCalls: List<CodeModeCall> = initial.calls.orEmpty()
    private var nextId: Int = pendingCalls.size + 1

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = advanceLock.withLock {
        checkOpen()
        var advanced = false
        try {
            val reply = cachedReply?.also {
                CodeModeFrames.validateResultSet(emptyList(), results)
                cachedReply = null
            } ?: receiveAfter(results)
            toStep(awaitInput(reply)).also { advanced = true }
        } catch (failure: CodeModeWorkerLostException) {
            stop()
            if (end.get() == End.CLOSED) unavailable()
            throw failure
        } finally {
            if (!advanced) close()
        }
    }

    fun stop() {
        if (end.compareAndSet(null, End.STOPPED)) release()
    }

    override fun close() {
        if (end.compareAndSet(null, End.CLOSED)) release()
    }

    private fun release() {
        lost.complete(Unit)
        channel.afterExit { onClose(this) }
        channel.close()
    }

    private fun checkOpen() {
        if (end.get() != null) unavailable()
    }

    private fun unavailable(): Nothing {
        if (end.get() == End.STOPPED) throw CodeModeWorkerLostException()
        error("Code-mode cell is closed")
    }

    /** A stop can close the channel after the caller passed its open check. */
    private suspend fun exchange(frame: JsonObject): JsonObject = try {
        channel.exchange(frame)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: IllegalStateException) {
        if (end.get() == End.STOPPED) unavailable()
        throw failure
    }

    private suspend fun receiveAfter(results: List<CodeModeResult>): WorkerReply {
        checkOpen()
        CodeModeFrames.validateResultSet(pendingCalls, results)
        val reply = CodeModeFrames.parseReply(
            frame = exchange(CodeModeWire.resultFrame(results)),
            tools = tools,
            nextId = nextId,
        )
        pendingCalls = reply.calls.orEmpty()
        nextId += pendingCalls.size
        return reply
    }

    private suspend fun awaitInput(initial: WorkerReply): WorkerReply {
        var reply = initial
        while (reply.waitingForInput) {
            val input = checkNotNull(source) { "Ordinary cell cannot wait for source input" }
            val part = nextInput(input)
            reply = CodeModeFrames.parseReply(
                exchange(StreamingCodeModeWire.inputFrame(part)), tools, nextId,
            )
        }
        pendingCalls = reply.calls.orEmpty()
        if (initial.waitingForInput) nextId += pendingCalls.size
        return reply
    }

    private suspend fun nextInput(input: CodeModeSource): CodeModeSourcePart = coroutineScope {
        val reading = async {
            try {
                input.read()
            } catch (error: CodeModeSourcePersistenceException) {
                throw error
            } catch (error: CodeModeInfrastructureException) {
                throw error
            } catch (error: java.io.IOException) {
                CodeModeSourcePart.Failed("Upstream source interrupted: ${error.message.orEmpty()}")
            }
        }
        try {
            select {
                lost.onAwait { unavailable() }
                reading.onAwait { it }
            }
        } finally {
            reading.cancel()
        }
    }

    private fun toStep(reply: WorkerReply): CodeModeStep = reply.calls?.let { calls ->
        CodeModeStep.Calls(calls)
    } ?: CodeModeStep.Completed(
        output = checkNotNull(reply.output),
        error = reply.error,
    ).also { close() }
}
