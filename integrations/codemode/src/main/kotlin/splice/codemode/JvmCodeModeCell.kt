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
import kotlinx.serialization.json.JsonPrimitive
import splice.codemode.wire.CodeModeFrames
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeSourcePersistenceException
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
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
    private var sourceEnded: Boolean = false

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
        CodeModeFrames.validatePartialResultSet(pendingCalls, results)
        val frames = CodeModeWire.resultsFrames(results)
        val returnedCalls = mutableListOf<CodeModeCall>()
        var finalReply: WorkerReply? = null
        var offset = 0
        frames.forEach { frame ->
            val count = if (frame["type"] == JsonPrimitive("results_part")) {
                CodeModeFrames.parseResultsPart(frame).size
            } else {
                CodeModeFrames.parseResults(frame).size
            }
            val chunk = results.subList(offset, offset + count)
            offset += count
            val reply = CodeModeFrames.parseReply(exchange(frame), tools, nextId)
            val ids = chunk.map(CodeModeResult::id).toSet()
            pendingCalls = pendingCalls.filterNot { it.id in ids }
            reply.calls?.let { calls ->
                returnedCalls += calls
                pendingCalls = pendingCalls + calls
                nextId += calls.size
            }
            finalReply = reply
        }
        val reply = checkNotNull(finalReply)
        return if (returnedCalls.isNotEmpty()) reply.copy(calls = returnedCalls) else reply
    }

    private suspend fun awaitInput(initial: WorkerReply): WorkerReply {
        var reply = initial
        while (true) {
            val part = nextInputPart(reply) ?: break
            sourceEnded = sourceEnded || part is CodeModeSourcePart.Complete || part is CodeModeSourcePart.Failed
            reply = CodeModeFrames.parseReply(
                exchange(StreamingCodeModeWire.inputFrame(part)),
                tools,
                nextId,
            )
            reply.calls?.let { calls ->
                pendingCalls = pendingCalls + calls
                nextId += calls.size
            }
        }
        if (reply.waitingForInput && sourceEnded) {
            throw IOException("Code-mode worker requested input after source ended")
        }
        return reply
    }

    private suspend fun nextInputPart(reply: WorkerReply): CodeModeSourcePart? = when {
        reply.waitingForInput -> nextRequiredSourcePart()
        reply.waitingForResults -> nextAvailableSourcePart(reply)
        else -> null
    }

    private suspend fun nextRequiredSourcePart(): CodeModeSourcePart? {
        if (sourceEnded) return null
        val input = checkNotNull(source) { "Ordinary cell cannot wait for source input" }
        return nextInput(input)
    }

    private suspend fun nextAvailableSourcePart(reply: WorkerReply): CodeModeSourcePart? {
        if (reply.output != null) return null
        if (pendingCalls.isEmpty()) return null
        return if (sourceEnded) null else source?.readAvailable()
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
        CodeModeStep.Calls(calls, reply.output, reply.error)
    } ?: if (reply.waitingForResults) {
        CodeModeStep.WaitingForResults(reply.output, reply.error)
    } else {
        CodeModeStep.Completed(
            output = checkNotNull(reply.output),
            error = reply.error,
        ).also { close() }
    }
}
