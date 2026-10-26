package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.StatusRuntimeException
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.grpc.state.NativeGrpcInputFailure
import splice.codemode.nativehost.trace.NativeTraceSpan
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.JsonScalars
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Each nested result uses an independent HTTP/2 stream; cancellations can precede invocation delivery. */
internal class NativeGrpcDelegates(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val sink: NativeGrpcDelegateSink,
    private val tracing: NativeTracing = NativeTracing(),
) {
    private val pending = ConcurrentHashMap<String, NativeGrpcDelegate>()
    private val completions = ConcurrentHashMap<NativeGrpcDelegate, Job>()

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
            completeAsync(key, delegate, result)
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
                releaseTrace(key, delegate)
            }
        }
    }

    fun answer(frame: JsonObject) {
        val id = requireNotNull(JsonScalars.str(frame, "id"))
        val delegate = pending[id] ?: return
        val result = frame.getValue("result").jsonObject
        completeAsync(id, delegate, result)
    }

    private fun completeAsync(id: String, delegate: NativeGrpcDelegate, result: JsonObject) {
        val job = scope.launch(
            delegate.span?.coroutineContext ?: kotlin.coroutines.EmptyCoroutineContext,
            start = CoroutineStart.LAZY,
        ) { complete(id, delegate, result) }
        completions[delegate] = job
        job.invokeOnCompletion {
            completions.remove(delegate, job)
            tracing.invocations.remove(id, delegate.span)
            delegate.span?.close()
        }
        delegate.start(job)
    }

    private suspend fun complete(id: String, delegate: NativeGrpcDelegate, result: JsonObject) {
        if (delegate.isCancelled()) return
        // Codex notifications settle locally; AcknowledgeNotification is a host API for other clients.
        if (delegate.notification) {
            answered(id, delegate)
            return
        }
        try {
            val message = NativeGrpcRequests.toolResult(delegate.session.id, delegate.id, result)
            NativeGrpcDeadlines.startup("tool invocation completion") {
                channel.unary(NativeGrpcRequest("CompleteToolCall", message, delegate.session.route, null))
            }
            answered(id, delegate)
        } catch (error: StatusRuntimeException) {
            // Only cancellation makes a missing invocation benign; an uncancelled failure closes its session.
            if (!delegate.isCancelled()) sink.failed(delegate.session, error)
        } catch (error: NativeGrpcProtocolException) {
            if (!delegate.isCancelled()) sink.failed(delegate.session, error)
        }
    }

    private fun answered(id: String, delegate: NativeGrpcDelegate) {
        if (pending.remove(id, delegate)) {
            sink.settled(delegate.session, delegate.cell, delegate.id)
            releaseTrace(id, delegate)
        }
    }

    private fun releaseTrace(id: String, delegate: NativeGrpcDelegate) {
        if (!completions.containsKey(delegate)) tracing.invocations.remove(id, delegate.span)
    }

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
        completeAsync(key(session, "tool", id), NativeGrpcDelegate(session, "", id, false), result)
    }

    private fun key(session: NativeGrpcSession, kind: String, id: String): String = JsonArray(
        listOf(JsonPrimitive(session.localId), JsonPrimitive(kind), JsonPrimitive(id)),
    ).toString()

    fun failed(session: NativeGrpcSession) {
        completions.keys.filter { it.session === session }.forEach(NativeGrpcDelegate::cancel)
        pending.entries.forEach { (id, delegate) ->
            if (delegate.session === session) {
                delegate.cancel()
                if (pending.remove(id, delegate)) {
                    sink.emit(NativeGrpcFrames.cancelled(JsonPrimitive(id)))
                    releaseTrace(id, delegate)
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

private class NativeGrpcDelegate(
    val session: NativeGrpcSession,
    val cell: String,
    val id: String,
    val notification: Boolean,
    val span: NativeTraceSpan? = null,
) {
    private val cancelled = AtomicBoolean()
    private var job: Job? = null

    @Synchronized
    fun cancel() {
        cancelled.set(true)
        job?.cancel()
        if (job == null) span?.close()
    }

    @Synchronized
    fun start(job: Job) {
        this.job = job
        if (cancelled.get()) job.cancel() else job.start()
    }

    fun isCancelled(): Boolean = cancelled.get()
}
