package splice.codemode.nativehost

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Buffers delegates even if the host issues one before execution acceptance reaches its caller. */
internal class NativeEvents {
    private val cells = ConcurrentHashMap<Pair<String, String>, Channel<JsonObject>>()
    private val delegates = ConcurrentHashMap<String, Pair<String, String>>()
    private val leases = ConcurrentHashMap<Pair<String, String>, NativeCellLease>()
    private val expected = ConcurrentHashMap<Long, NativeOwnership>()
    private val earlyCompletions = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    @Volatile private var failure: IOException? = null

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

    fun expect(operationId: Long, sessionId: String, lease: NativeCellLease) {
        expected[operationId] = NativeOwnership(sessionId, lease)
    }

    fun accepted(frame: JsonObject) {
        val id = requireNotNull(JsonScalars.long(frame, "id"))
        val ownership = expected.remove(id) ?: return
        val result = frame.getValue("result").jsonObject
        if (JsonScalars.str(result, "status") == "ok") {
            val value = result.getValue("value").jsonObject
            val cellId = requireNotNull(JsonScalars.str(value, "cellId"))
            val key = ownership.sessionId to cellId
            if (earlyCompletions.remove(key)) ownership.lease.close() else leases[key] = ownership.lease
        } else {
            ownership.lease.close()
        }
    }

    fun discard(operationId: Long) {
        expected.remove(operationId)?.lease?.close()
    }

    fun completed(frame: JsonObject) {
        val sessionId = requireNotNull(JsonScalars.str(frame, "sessionId"))
        val cellId = requireNotNull(JsonScalars.str(frame, "cellId"))
        val key = sessionId to cellId
        val lease = leases.remove(key)
        if (lease != null) {
            lease.close()
        } else if (earlyCompletions.size < expected.size && expected.values.any { it.sessionId == sessionId }) {
            earlyCompletions.add(key)
        }
    }

    fun release(sessionId: String, id: String) {
        val key = sessionId to id
        // Keep the receipt until cell/closed arrives, so an older terminal cell cannot consume a newer start's slot.
        leases[key]?.close()
        cells.remove(key)?.close()
        delegates.entries.removeIf { it.value == key }
    }

    fun fail(error: IOException) {
        failure = error
        cells.values.forEach { it.close(error) }
        leases.values.forEach(NativeCellLease::close)
        leases.clear()
        expected.values.forEach { it.lease.close() }
        expected.clear()
        earlyCompletions.clear()
    }

    fun failSession(sessionId: String, error: IOException) {
        cells.forEach { (key, channel) -> if (key.first == sessionId) channel.close(error) }
        leases.entries.forEach { (key, lease) ->
            if (key.first == sessionId && leases.remove(key, lease)) lease.close()
        }
        expected.entries.forEach { (id, ownership) ->
            if (ownership.sessionId == sessionId && expected.remove(id, ownership)) ownership.lease.close()
        }
        earlyCompletions.removeIf { it.first == sessionId }
        delegates.entries.removeIf { it.value.first == sessionId }
    }
}

private data class NativeOwnership(val sessionId: String, val lease: NativeCellLease)
