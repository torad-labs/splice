package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Canceled waits retire before replacement; yielding the initial observation retains its cell and output. */
internal class NativeObservations(
    private val connection: NativeConnection,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val cellId: String,
    initial: NativeOperation,
) {
    private val execution = initial.id
    private val abandoned = AtomicBoolean()

    @Volatile private var current: NativeObservation? = NativeObservation(
        initial.id,
        requireNotNull(initial.initial),
        initial = true,
    )
    private var retirement: Deferred<Unit>? = null
    private var initialClaimed = false

    suspend fun response(yieldTimeMs: BigInteger): CompletableDeferred<JsonObject> {
        retirement?.await()
        retirement = null
        current?.let {
            if (it.initial && !initialClaimed) {
                initialClaimed = true
                // The accepted cell is owned already; observer cancellation cannot abandon its ownership handoff.
                withContext(NonCancellable) {
                    connection.send(NativeRequests.control("operation/observe", it.id))
                }
            }
            return it.response
        }
        val operation = connection.prepare()
        val observation = NativeObservation(operation.id, operation.response, initial = false)
        current = observation
        try {
            connection.dispatch(operation, NativeRequests.wait(sessionId, cellId, yieldTimeMs))
        } finally {
            if (!operation.wasDispatched()) {
                connection.discard(operation)
                current = null
            }
        }
        return operation.response
    }

    fun consumed() {
        current = null
    }

    fun cancelled() {
        val observation = current ?: return
        if (observation.initial) {
            scope.launch { connection.send(NativeRequests.control("operation/yield", observation.id)) }
        } else if (retirement == null) {
            retirement = scope.async {
                connection.send(NativeRequests.control("operation/cancel", observation.id))
                val frame = observation.response.await()
                val result = frame["result"] as? JsonObject
                // A completed observation can win the race with cancellation. Retain that output for its caller.
                if (JsonScalars.str(result, "status") == "error" && current === observation) current = null
            }
        }
    }

    suspend fun yieldObservation() {
        current?.let { connection.send(NativeRequests.control("operation/yield", it.id)) }
    }

    suspend fun abandon() {
        if (abandoned.compareAndSet(false, true)) {
            connection.send(NativeRequests.control("operation/abandon", execution))
        }
    }
}

private data class NativeObservation(
    val id: Long,
    val response: CompletableDeferred<JsonObject>,
    val initial: Boolean,
)
