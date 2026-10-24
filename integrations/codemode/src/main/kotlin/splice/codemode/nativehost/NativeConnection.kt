package splice.codemode.nativehost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one stdio connection. Host loss fails retained cells; it never silently replays accepted source. */
internal class NativeConnection(
    private val process: Process,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onClosed: NativeConnectionClosed,
) : AutoCloseable {
    val generation: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()
    private val writer = Mutex()
    private val output = DataOutputStream(process.outputStream)
    private val operations = NativeOperations()
    val events = NativeEvents()
    private val ready = CompletableDeferred<JsonObject>()

    init {
        scope.launch {
            try {
                DataInputStream(process.inputStream).use { input ->
                    while (!closed.get()) route(NativeFrames.read(input))
                }
            } catch (error: IOException) {
                fail(error)
            } catch (error: IllegalArgumentException) {
                fail(error)
            } catch (error: IllegalStateException) {
                fail(error)
            }
        }
        scope.launch {
            try {
                process.errorStream.use { it.transferTo(OutputStream.nullOutputStream()) }
            } catch (error: IOException) {
                if (!closed.get()) fail(error)
            }
        }
    }

    suspend fun handshake() {
        send(
            buildJsonObject {
                put("type", "connection/hello")
                put("supportedVersions", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)) })
                put("requiredCapabilities", buildJsonArray {})
                put("optionalCapabilities", buildJsonArray {})
            },
        )
        val hello = ready.await()
        if (JsonScalars.str(hello, "type") != "connection/ready" ||
            JsonScalars.int(hello, "selectedVersion") != 1
        ) {
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
        val payload = NativeFrames.encode(frame)
        withContext(ioDispatcher) {
            writer.withLock {
                if (closed.get()) throw CodeModeWorkerLostException()
                try {
                    operation?.markDispatched()
                    NativeFrames.write(output, payload)
                } catch (error: IOException) {
                    fail(error)
                    throw CodeModeWorkerLostException(error)
                }
            }
        }
    }

    private fun route(frame: JsonObject) {
        when (JsonScalars.str(frame, "type")) {
            "connection/ready", "connection/rejected" -> ready.complete(frame)
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

    private fun fail(cause: Throwable) {
        val error = CodeModeWorkerLostException(cause)
        ready.completeExceptionally(error)
        operations.fail(error)
        events.fail(error)
        close()
    }

    fun afterExit(action: Runnable) {
        process.onExit().thenRun(action)
    }

    fun isAvailable(): Boolean = !closed.get() && process.isAlive

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val error = CodeModeWorkerLostException()
            ready.completeExceptionally(error)
            operations.fail(error)
            events.fail(error)
            process.destroyForcibly()
            onClosed.closed(this)
        }
    }
}

internal fun interface NativeConnectionClosed {
    fun closed(connection: NativeConnection)
}
