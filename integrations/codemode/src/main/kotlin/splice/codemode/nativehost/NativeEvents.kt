package splice.codemode.nativehost

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.failure.CodeModeWorkerLostException
import java.util.concurrent.ConcurrentHashMap

/** Buffers delegates even if the host issues one before execution acceptance reaches its caller. */
internal class NativeEvents {
    private val cells = ConcurrentHashMap<Pair<String, String>, Channel<JsonObject>>()
    private val delegates = ConcurrentHashMap<String, Pair<String, String>>()

    @Volatile private var failure: CodeModeWorkerLostException? = null

    fun cell(sessionId: String, id: String): Channel<JsonObject> = cells.computeIfAbsent(sessionId to id) {
        Channel<JsonObject>(Channel.UNLIMITED).also { channel -> failure?.let(channel::close) }
    }

    fun deliver(frame: JsonObject) {
        val request = frame.getValue("request").jsonObject
        val invocation = request["invocation"] as? JsonObject
        val id = JsonScalars.str(invocation, "cell_id") ?: JsonScalars.str(request, "cellId")
            ?: error("Native delegate has no cell id")
        val sessionId = JsonScalars.str(frame, "sessionId") ?: error("Native delegate has no session id")
        delegates[frame.getValue("id").toString()] = sessionId to id
        check(cell(sessionId, id).trySend(frame).isSuccess) { "Native delegate arrived for a closed cell" }
    }

    fun cancel(frame: JsonObject) {
        delegates.remove(frame.getValue("id").toString())?.let { key ->
            cell(key.first, key.second).trySend(frame)
        }
    }

    fun answered(delegateId: String) {
        delegates.remove(delegateId)
    }

    fun release(sessionId: String, id: String) {
        val key = sessionId to id
        cells.remove(key)?.close()
        delegates.entries.removeIf { it.value == key }
    }

    fun fail(error: CodeModeWorkerLostException) {
        failure = error
        cells.values.forEach { it.close(error) }
    }
}
