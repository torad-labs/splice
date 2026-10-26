package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.StatusRuntimeException
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.NATIVE_RESOURCE_LIMITS_CAPABILITY
import splice.codemode.nativehost.NATIVE_YIELD_CAPABILITY
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.trace.NativeTraceElement
import splice.core.util.JsonScalars
import splice.upstream.failure.CODE_MODE_SESSION_CLOSED_ERROR
import java.io.IOException

/** Translates the existing internal envelopes into the current host's independent RPCs. */
internal class NativeGrpcOperationsBridge(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val sessions: NativeGrpcSessions,
    private val controls: NativeGrpcControls,
    private val delegates: NativeGrpcDelegates,
    private val sink: NativeGrpcRunSink,
) {
    fun send(frame: JsonObject, operation: NativeOperation?) {
        when (val type = JsonScalars.str(frame, "type")) {
            "connection/hello" -> hello()
            "operation/request" -> operation(frame, operation)
            "delegate/response" -> delegates.answer(frame)
            "operation/observe", "operation/cancel", "operation/yield", "operation/abandon" -> {
                val id = requireNotNull(JsonScalars.long(frame, "id"))
                scope.launch { controls.control(type, id) }
            }
            else -> throw IOException("Unknown internal CodeMode frame")
        }
    }

    private fun operation(frame: JsonObject, operation: NativeOperation?) {
        val id = requireNotNull(JsonScalars.long(frame, "id"))
        val request = frame.getValue("request").jsonObject
        val alias = requireNotNull(JsonScalars.str(request, "sessionId"))
        if (JsonScalars.str(request, "method") == "session/open") {
            val message = NativeGrpcRequests.open(request)
            operation?.markDispatched()
            launch(id, null, "session opening", owner = operation) {
                sessions.open(alias, message)
                buildJsonObject { put("type", "session/ready"); put("sessionId", alias) }
            }
            return
        }
        val session = sessions.get(alias)
        if (session == null) {
            if (JsonScalars.str(request, "method") == "session/shutdown") {
                sink.emit(NativeGrpcFrames.response(id, closed(alias)))
            } else {
                sink.emit(NativeGrpcFrames.failed(id, CODE_MODE_SESSION_CLOSED_ERROR))
            }
            return
        }
        dispatch(id, operation, session, request)
    }

    private fun dispatch(id: Long, owner: NativeOperation?, session: NativeGrpcSession, request: JsonObject) {
        when (JsonScalars.str(request, "method")) {
            "session/execute" -> controls.execute(owner, id, session, request.getValue("request").jsonObject)
            "session/wait" -> controls.wait(owner, id, session, request.getValue("request").jsonObject)
            "session/terminate" -> {
                val cell = requireNotNull(JsonScalars.str(request, "cellId"))
                val message = NativeGrpcWire.message(
                    "TerminateRequest",
                    mapOf("session_id" to session.id, "cell_id" to cell),
                )
                owner?.markDispatched()
                launch(id, session, "termination", owner = owner) { terminate(session, cell, message) }
            }
            "session/shutdown" -> {
                owner?.markDispatched()
                launch(id, session, "session shutdown") {
                    sessions.close(session.localId)
                    sink.invalidate(session, IOException("CodeMode session closed"))
                    closed(session.localId)
                }
            }
            else -> throw IOException("Unknown CodeMode operation")
        }
    }

    private suspend fun terminate(session: NativeGrpcSession, cell: String, message: DynamicMessage): JsonObject {
        val response = NativeGrpcDeadlines.startup("termination") {
            channel.unary(NativeGrpcRequest("Terminate", message, session.route, timeoutMillis = null))
        }
        val value = NativeGrpcResponses.wait(response)
        val live = NativeGrpcFields.optional(response.message, "live_cell")
        val outcome = live ?: NativeGrpcFields.message(response.message, "missing_cell")
        val actual = NativeGrpcFields.identifier(outcome, "cell_id")
        if (actual != cell) {
            throw NativeGrpcProtocolException(
                "gRPC code-mode host returned cell $actual instead of $cell",
                invalidatesSession = true,
            )
        }
        if (NativeGrpcFields.optional(outcome, "yielded") == null) {
            sink.settle(session, cell, NativeGrpcFields.optional(outcome, "terminated") != null)
        }
        return value
    }

    private fun launch(
        id: Long,
        session: NativeGrpcSession?,
        activity: String,
        owner: NativeOperation? = null,
        action: NativeGrpcCommand,
    ) {
        val task = scope.launch(
            traceContext(owner),
            start = CoroutineStart.LAZY,
        ) {
            try {
                sink.emit(NativeGrpcFrames.response(id, action.execute()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: StatusRuntimeException) {
                sink.emit(NativeGrpcFrames.failed(id, NativeGrpcErrors.render(error, activity)))
                NativeGrpcErrors.invalidate(sink, session, error)
            } catch (error: NativeGrpcProtocolException) {
                sink.emit(NativeGrpcFrames.failed(id, error.diagnostic))
                NativeGrpcErrors.invalidate(sink, session, error)
            } catch (error: IOException) {
                sink.emit(NativeGrpcFrames.failed(id, NativeGrpcErrors.render(error, activity)))
            } catch (error: IllegalArgumentException) {
                sink.emit(NativeGrpcFrames.failed(id, NativeGrpcErrors.render(error, activity)))
            } catch (error: IllegalStateException) {
                sink.emit(NativeGrpcFrames.failed(id, NativeGrpcErrors.render(error, activity)))
            }
        }
        val cancellation = owner?.response?.invokeOnCompletion { error ->
            if (error is CancellationException) task.cancel(error)
        }
        task.invokeOnCompletion {
            cancellation?.dispose()
            if (owner?.response?.isCancelled == true) owner.discard()
        }
        task.start()
    }

    private fun traceContext(owner: NativeOperation?): NativeTraceElement =
        NativeTraceElement(owner?.traceContext ?: Context.root())

    private fun closed(alias: String): JsonObject = buildJsonObject {
        put("type", "session/closed")
        put("sessionId", alias)
    }

    private fun hello() {
        // This is an internal adapter acknowledgement, not a synthetic native handshake.
        // Native identity and the exact protobuf descriptor are verified before the process starts.
        sink.emit(
            buildJsonObject {
                put("type", "connection/ready")
                put("selectedVersion", 1)
                put(
                    "capabilities",
                    buildJsonArray {
                        add(JsonPrimitive(NATIVE_RESOURCE_LIMITS_CAPABILITY))
                        add(JsonPrimitive(NATIVE_YIELD_CAPABILITY))
                    },
                )
            },
        )
    }
}

internal fun interface NativeGrpcCommand {
    suspend fun execute(): JsonObject
}
