package splice.codemode.nativehost

import io.opentelemetry.context.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.failure.CodeModeToolException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class NativeOperation(
    val id: Long,
    val response: CompletableDeferred<JsonObject>,
    val initial: CompletableDeferred<JsonObject>? = null,
    private val registry: NativeOperations,
    val traceContext: Context = Context.current(),
) {
    private val dispatched = AtomicBoolean()

    fun markDispatched() {
        dispatched.set(true)
    }

    fun wasDispatched(): Boolean = dispatched.get()

    /** Called only after this operation's driver can no longer emit a response. */
    fun discard(): Unit = registry.discard(this)
}

/** An execution's acceptance and initial observation are separate, independently routed responses. */
internal class NativeOperations {
    private val sequence = AtomicLong()
    private val responses = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val initials = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    @Volatile private var failure: IOException? = null

    fun register(execute: Boolean): NativeOperation {
        val id = sequence.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        val initial = if (execute) CompletableDeferred<JsonObject>() else null
        responses[id] = response
        if (initial != null) initials[id] = initial
        failure?.let { error -> fail(error) }
        return NativeOperation(id, response, initial, this)
    }

    fun deliver(frame: JsonObject) {
        val id = JsonScalars.long(frame, "id") ?: error("Native operation has no id")
        val table = if (JsonScalars.str(frame, "type") == "execute/initialResponse") initials else responses
        val waiting = table.remove(id) ?: throw IOException("Native host returned an unknown operation id")
        waiting.complete(frame)
        val result = frame["result"]?.jsonObject
        if (table === responses && JsonScalars.str(result, "status") == "error") {
            initials.remove(
                id,
            )?.completeExceptionally(CodeModeToolException(JsonScalars.str(result, "message").orEmpty()))
        }
    }

    fun discard(operation: NativeOperation) {
        responses.remove(operation.id)?.cancel()
        initials.remove(operation.id)?.cancel()
    }

    fun fail(error: IOException) {
        failure = error
        listOf(responses, initials).forEach { table ->
            table.entries.forEach { (id, deferred) ->
                if (table.remove(id, deferred)) deferred.completeExceptionally(error)
            }
        }
    }
}
