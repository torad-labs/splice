package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class NativeOperation(
    val id: Long,
    val response: CompletableDeferred<JsonObject>,
    val initial: CompletableDeferred<JsonObject>? = null,
) {
    private val dispatched = AtomicBoolean()

    fun markDispatched() {
        dispatched.set(true)
    }

    fun wasDispatched(): Boolean = dispatched.get()
}

/** An execution's acceptance and initial observation are separate, independently routed responses. */
internal class NativeOperations {
    private val sequence = AtomicLong()
    private val responses = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val initials = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    @Volatile private var failure: CodeModeWorkerLostException? = null

    fun register(execute: Boolean): NativeOperation {
        val id = sequence.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        val initial = if (execute) CompletableDeferred<JsonObject>() else null
        responses[id] = response
        if (initial != null) initials[id] = initial
        failure?.let { error -> fail(error) }
        return NativeOperation(id, response, initial)
    }

    fun deliver(frame: JsonObject) {
        val id = JsonScalars.long(frame, "id") ?: error("Native operation has no id")
        val table = if (JsonScalars.str(frame, "type") == "execute/initialResponse") initials else responses
        table.remove(id)?.complete(frame)
        val result = frame["result"]?.jsonObject
        if (table === responses && JsonScalars.str(result, "status") == "error") {
            initials.remove(id)?.completeExceptionally(IOException(JsonScalars.str(result, "message")))
        }
    }

    fun discard(operation: NativeOperation) {
        responses.remove(operation.id)
        initials.remove(operation.id)
    }

    fun fail(error: CodeModeWorkerLostException) {
        failure = error
        listOf(responses, initials).forEach { table ->
            table.entries.forEach { (id, deferred) ->
                if (table.remove(id, deferred)) deferred.completeExceptionally(error)
            }
        }
    }
}
