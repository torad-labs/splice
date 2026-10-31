package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.grpc.delegate.NativeGrpcDelegate
import splice.codemode.nativehost.grpc.delegate.NativeGrpcDelegateCompletions
import splice.codemode.nativehost.grpc.state.NativeGrpcInputFailure
import splice.codemode.nativehost.trace.NativeTraceSpan
import splice.codemode.nativehost.trace.NativeTracing
import java.util.concurrent.ConcurrentHashMap

/** Each nested result uses an independent HTTP/2 stream; cancellations can precede invocation delivery. How a
 *  delegate is answered is [NativeGrpcDelegateCompletions]'s part. */
internal class NativeGrpcDelegates(
    channel: NativeGrpcChannel,
    scope: CoroutineScope,
    private val sink: NativeGrpcDelegateSink,
    private val tracing: NativeTracing = NativeTracing(),
) {
    private val pending = ConcurrentHashMap<String, NativeGrpcDelegate>()
    private val completions = NativeGrpcDelegateCompletions(channel, scope, sink, tracing, pending)

    fun tool(session: NativeGrpcSession, message: DynamicMessage) {
        val id = NativeGrpcFields.identifier(message, "invocation_id")
        val cell = NativeGrpcFields.identifier(message, "cell_id")
        val key = key(session, "tool", id)
        val span = callbackSpan(session, message)
        val delegate = NativeGrpcDelegate(session, cell, id, false, span)
        span.handoff {
            check(pending.putIfAbsent(key, delegate) == null) {
                "Duplicate CodeMode invocation"
            }
            tracing.invocations.add(key, span)
        }
        val request = try {
            NativeGrpcInvocations.tool(message)
        } catch (error: NativeGrpcInputFailure) {
            val result = buildJsonObject {
                put("status", "error")
                put("message", error.diagnostic)
            }
            completions.completeAsync(key, delegate, result)
            return
        }
        sink.emit(NativeGrpcFrames.delegate(key, session.localId, request))
    }

    private fun callbackSpan(session: NativeGrpcSession, message: DynamicMessage): NativeTraceSpan = tracing.start(
        "code_mode.grpc.callback",
        mapOf(
            "session.id" to session.id,
            "execution.id" to NativeGrpcFields.text(message, "execution_id"),
            "cell.id" to NativeGrpcFields.text(message, "cell_id"),
            "invocation.id" to NativeGrpcFields.text(message, "invocation_id"),
        ),
        remoteParent = NativeGrpcWire.value(message, "traceparent") as? String,
        current = Context.root(),
    )

    fun ignored(session: NativeGrpcSession, message: DynamicMessage) {
        callbackSpan(session, message).close()
    }

    fun notification(session: NativeGrpcSession, message: DynamicMessage, receipt: String) {
        val cell = NativeGrpcFields.identifier(message, "cell_id")
        val key = key(session, "notification", receipt)
        check(pending.putIfAbsent(key, NativeGrpcDelegate(session, cell, receipt, true)) == null) {
            "Duplicate CodeMode notification"
        }
        sink.emit(NativeGrpcFrames.delegate(key, session.localId, NativeGrpcInvocations.notification(message)))
    }

    fun cancelled(session: NativeGrpcSession, id: String) {
        val key = key(session, "tool", id)
        val delegate = pending[key]
        if (delegate != null) {
            check(delegate.session === session) { "CodeMode cancellation crossed sessions" }
            delegate.cancel()
            if (pending.remove(key, delegate)) {
                sink.emit(NativeGrpcFrames.cancelled(JsonPrimitive(key)))
                sink.settled(session, delegate.cell, id)
                completions.releaseTrace(key, delegate)
            }
        }
    }

    fun answer(frame: JsonObject) = completions.answer(frame)

    fun reject(
        session: NativeGrpcSession,
        message: DynamicMessage,
        error: String = "CodeMode execution is no longer accepting tool calls",
    ) {
        ignored(session, message)
        val id = NativeGrpcFields.identifier(message, "invocation_id")
        val result = buildJsonObject {
            put("status", "error")
            put("message", error)
        }
        completions.completeAsync(key(session, "tool", id), NativeGrpcDelegate(session, "", id, false), result)
    }

    private fun key(session: NativeGrpcSession, kind: String, id: String): String = JsonArray(
        listOf(JsonPrimitive(session.localId), JsonPrimitive(kind), JsonPrimitive(id)),
    ).toString()

    fun failed(session: NativeGrpcSession) {
        completions.cancelRunning(session)
        pending.entries.forEach { (id, delegate) ->
            if (delegate.session === session) {
                delegate.cancel()
                if (pending.remove(id, delegate)) {
                    sink.emit(NativeGrpcFrames.cancelled(JsonPrimitive(id)))
                    completions.releaseTrace(id, delegate)
                }
            }
        }
    }

    fun terminateNotifications(session: NativeGrpcSession, cell: String) {
        pending.entries.filter { (_, value) -> value.session === session && value.cell == cell && value.notification }
            .forEach { (id, delegate) ->
                delegate.cancel()
                if (pending.remove(id, delegate)) {
                    sink.emit(NativeGrpcFrames.cancelled(JsonPrimitive(id)))
                    sink.settled(session, cell, delegate.id)
                }
            }
    }
}

internal interface NativeGrpcDelegateSink {
    fun emit(frame: JsonObject)
    fun failed(session: NativeGrpcSession, error: Throwable)
    fun settled(session: NativeGrpcSession, cell: String, id: String)
}
