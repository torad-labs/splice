package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.grpc.NativeGrpcHostTransport
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.JsonScalars
import splice.upstream.failure.CODE_MODE_SESSION_CLOSED_ERROR
import splice.upstream.failure.CodeModeToolException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal const val NATIVE_RESOURCE_LIMITS_CAPABILITY: String = "session-cell-execution-resource-limits"
internal const val NATIVE_YIELD_CAPABILITY: String = "yield-observation"

/** Owns one stdio connection. Host loss fails retained cells; it never silently replays accepted source. */
internal class NativeConnection(
    process: Process,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    private val onClosed: NativeConnectionClosed,
    val tracing: NativeTracing = NativeTracing(),
) : AutoCloseable {
    val generation: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()
    private val operations = NativeOperations()
    val events = NativeEvents()
    private val ready = CompletableDeferred<JsonObject>()

    private val transport = NativeGrpcHostTransport(process, scope, ioDispatcher, this)

    init {
        transport.start()
    }

    suspend fun handshake() {
        send(
            buildJsonObject {
                put("type", "connection/hello")
                put("supportedVersions", buildJsonArray { add(JsonPrimitive(1)) })
                put(
                    "requiredCapabilities",
                    buildJsonArray {
                        add(JsonPrimitive(NATIVE_RESOURCE_LIMITS_CAPABILITY))
                        add(JsonPrimitive(NATIVE_YIELD_CAPABILITY))
                    },
                )
                put("optionalCapabilities", buildJsonArray {})
            },
        )
        val hello = ready.await()
        val protocolMatches = JsonScalars.str(hello, "type") == "connection/ready" &&
            JsonScalars.int(hello, "selectedVersion") == 1
        val capabilities = (hello["capabilities"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        val required = listOf(JsonPrimitive(NATIVE_RESOURCE_LIMITS_CAPABILITY), JsonPrimitive(NATIVE_YIELD_CAPABILITY))
        if (!protocolMatches || !capabilities.containsAll(required)) {
            throw IOException("Native code-mode protocol V1 handshake rejected")
        }
    }

    suspend fun operation(request: JsonObject, execute: Boolean = false): NativeOperation {
        val operation = prepare(execute)
        try {
            dispatch(operation, request)
        } finally {
            if (!operation.wasDispatched()) discard(operation)
        }
        return operation
    }

    fun prepare(execute: Boolean = false): NativeOperation = operations.register(execute)

    fun discard(operation: NativeOperation) {
        operations.discard(operation)
        events.discard(operation.id)
    }

    suspend fun dispatch(operation: NativeOperation, request: JsonObject) {
        send(
            buildJsonObject {
                put("type", "operation/request")
                put("id", operation.id)
                put("request", request)
            },
            operation,
        )
    }

    suspend fun send(frame: JsonObject, operation: NativeOperation? = null) {
        transport.send(frame, operation)
    }

    fun cancelObservation(operation: Long) = transport.cancelObservation(operation)

    fun receive(frame: JsonObject) {
        when (JsonScalars.str(frame, "type")) {
            "connection/ready", "connection/rejected" -> {
                if (!ready.complete(frame)) throw IOException("Native host sent a second handshake response")
            }
            "operation/response" -> {
                events.accepted(frame)
                operations.deliver(frame)
            }
            "execute/initialResponse" -> operations.deliver(frame)
            "delegate/request" -> events.deliver(frame)
            "delegate/cancel" -> events.cancel(frame)
            "cell/closed" -> events.completed(frame) // Release the heap reservation while preserving pending output.
            else -> throw IOException("Unknown native code-mode host frame type")
        }
    }

    fun fail(cause: Throwable) {
        val error = CodeModeToolException(CODE_MODE_SESSION_CLOSED_ERROR, cause)
        ready.completeExceptionally(error)
        operations.fail(error)
        events.fail(error)
        close()
    }

    fun afterExit(action: Runnable) {
        transport.afterExit(action)
    }

    fun isAvailable(): Boolean = !closed.get() && transport.isAvailable()

    fun hasSession(id: String): Boolean = transport.hasSession(id)

    fun failSession(sessionId: String, cause: Throwable) {
        events.failSession(sessionId, CodeModeToolException(CODE_MODE_SESSION_CLOSED_ERROR, cause))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val error = CodeModeToolException(CODE_MODE_SESSION_CLOSED_ERROR)
            ready.completeExceptionally(error)
            operations.fail(error)
            events.fail(error)
            transport.close()
            tracing.invocations.close()
            onClosed.closed(this)
        }
    }
}

internal fun interface NativeConnectionClosed {
    fun closed(connection: NativeConnection)
}
