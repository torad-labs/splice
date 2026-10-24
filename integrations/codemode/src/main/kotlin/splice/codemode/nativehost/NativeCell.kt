package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.util.concurrent.atomic.AtomicBoolean

/** Delegate results resume their original promises; observing a yield never cancels execution. */
internal class NativeCell(
    override val cellId: String,
    private val sessionId: String,
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    private val initial: CompletableDeferred<JsonObject>,
    private val tools: Set<String>,
) : NativeCodeModeCell {
    private val closed = AtomicBoolean()
    private val advancing = Mutex()
    private val submitting = Mutex()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, JsonElement>()
    private val readySteps = ArrayDeque<CodeModeStep>()
    private var observation: CompletableDeferred<JsonObject>? = initial

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_NATIVE_OBSERVE_MS)
    }

    override suspend fun submitResults(results: List<CodeModeResult>): Unit = submitting.withLock {
        check(!closed.get()) { "Native cell is closed" }
        require(results.map { it.id }.distinct().size == results.size) { "Duplicate native callback results" }
        require(results.all { it.id in pending }) { "Result does not belong to a pending native callback" }
        results.forEach { result ->
            val id = pending.getValue(result.id)
            connection.send(NativeRequests.delegate(id, result))
            connection.events.answered(id.toString())
            pending.remove(result.id)
        }
    }

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = advancing.withLock {
        require(yieldTimeMs >= 0) { "Native observation time must be nonnegative" }
        check(!closed.get()) { "Native cell is closed" }
        observeLocked(yieldTimeMs)
    }

    private suspend fun observeLocked(yieldTimeMs: Long): CodeModeStep {
        readySteps.removeFirstOrNull()?.let { return it }
        val response = observation ?: connection.operation(
            NativeRequests.wait(sessionId, cellId, yieldTimeMs),
        ).response.also { observation = it }
        var step: CodeModeStep? = null
        while (step == null) {
            step = select {
                connection.events.cell(sessionId, cellId).onReceive { frame -> delegate(frame) }
                response.onAwait { frame ->
                    observation = null
                    NativeOutput.decode(NativeFrames.value(frame)).also { result ->
                        if (result is CodeModeStep.Completed) release()
                    }
                }
            }
        }
        return if (step is CodeModeStep.Calls) batchCalls(step) else step
    }

    /** Let the reader route the same native event-loop turn, then issue ready siblings in one client batch. */
    private suspend fun batchCalls(first: CodeModeStep.Calls): CodeModeStep.Calls {
        yield()
        val calls = first.calls.toMutableList()
        val channel = connection.events.cell(sessionId, cellId)
        var draining = true
        while (draining) {
            val frame = channel.tryReceive().getOrNull()
            if (frame == null) {
                draining = false
            } else {
                when (val next = delegate(frame)) {
                    is CodeModeStep.Calls -> calls.addAll(next.calls)
                    null -> Unit
                    else -> {
                        readySteps.addLast(next)
                        draining = false
                    }
                }
            }
        }
        return CodeModeStep.Calls(calls)
    }

    private suspend fun delegate(frame: JsonObject): CodeModeStep? {
        val id = frame.getValue("id")
        if (JsonScalars.str(frame, "type") == "delegate/cancel") {
            pending.entries.removeIf { it.value == id }
            return null
        }
        val request = frame.getValue("request").jsonObject
        return when (JsonScalars.str(request, "type")) {
            "notification/send" -> {
                connection.send(NativeRequests.notified(id))
                connection.events.answered(id.toString())
                CodeModeStep.Notified(JsonScalars.str(request, "text").orEmpty())
            }
            "tool/invoke" -> invoke(id, request.getValue("invocation").jsonObject)
            else -> error("Unknown native delegate request")
        }
    }

    private fun invoke(id: JsonElement, invocation: JsonObject): CodeModeStep {
        val tool = invocation.getValue("tool_name").jsonObject
        val name = JsonScalars.str(tool, "name") ?: error("Native tool callback has no name")
        check(name in tools) { "Native callback requested a tool outside the client catalog" }
        val callId = "native-$id"
        pending[callId] = id
        val arguments = invocation["input"] as? JsonObject ?: JsonObject(emptyMap())
        return CodeModeStep.Calls(listOf(CodeModeCall(callId, name, arguments)))
    }

    override suspend fun terminate(): CodeModeStep {
        check(!closed.get()) { "Native cell is closed" }
        val operation = connection.operation(NativeRequests.terminate(sessionId, cellId))
        return NativeOutput.decode(NativeFrames.value(operation.response.await())).also {
            closed.set(true)
            pending.clear()
            if (!advancing.isLocked) connection.events.release(sessionId, cellId)
        }
    }

    private fun release() {
        closed.set(true)
        pending.clear()
        connection.events.release(sessionId, cellId)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.launch {
                Cancellables.runCatchingBestEffort {
                    connection.operation(NativeRequests.terminate(sessionId, cellId)).response.await()
                }
                if (!advancing.isLocked) connection.events.release(sessionId, cellId)
            }
        }
    }
}

// Matches Codex's foreground default; the caller's explicit wait budget overrides this.
internal const val DEFAULT_NATIVE_OBSERVE_MS: Long = 10_000
