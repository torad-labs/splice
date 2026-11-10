package splice.codemode.nativehost.grpc.delegate

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.codemode.nativehost.grpc.NativeGrpcChannel
import splice.codemode.nativehost.grpc.NativeGrpcDeadlines
import splice.codemode.nativehost.grpc.NativeGrpcDelegateSink
import splice.codemode.nativehost.grpc.NativeGrpcProtocolException
import splice.codemode.nativehost.grpc.NativeGrpcRequest
import splice.codemode.nativehost.grpc.NativeGrpcRequests
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.JsonScalars
import java.util.concurrent.ConcurrentHashMap

/** How a delegate is answered. The client's result goes back to the host as CompleteToolCall, a notification
 *  settles here, and the delegate leaves the pending table once. */
internal class NativeGrpcDelegateCompletions(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val sink: NativeGrpcDelegateSink,
    private val tracing: NativeTracing,
    private val pending: ConcurrentHashMap<String, NativeGrpcDelegate>,
) {
    private val completions = ConcurrentHashMap<NativeGrpcDelegate, Job>()

    fun answer(frame: JsonObject) {
        val id = requireNotNull(JsonScalars.str(frame, "id"))
        val delegate = pending[id] ?: return
        val result = frame.getValue("result").jsonObject
        completeAsync(id, delegate, result)
    }

    fun completeAsync(id: String, delegate: NativeGrpcDelegate, result: JsonObject) {
        delegate.span?.replyReceived()
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
            answered(id, delegate, result)
            return
        }
        try {
            val message = NativeGrpcRequests.toolResult(delegate.session.id, delegate.id, result)
            // What the client answered is how the tool ended, decided as the answer goes out. The host finishes its
            // cell on this answer, and the cell's closing can reach us before the acknowledgement does; that closing
            // cancels only a callback nothing has decided.
            delegate.span?.outcomeIfUndecided(answerOutcome(result))
            NativeGrpcDeadlines.startup("tool invocation completion") {
                channel.unary(NativeGrpcRequest("CompleteToolCall", message, delegate.session.route, null))
            }
            answered(id, delegate, result)
        } catch (error: StatusRuntimeException) {
            delegate.span?.outcome("bad")
            // Only cancellation makes a missing invocation benign; an uncancelled failure closes its session.
            if (!delegate.isCancelled()) sink.failed(delegate.session, error)
        } catch (error: NativeGrpcProtocolException) {
            delegate.span?.outcome("bad")
            if (!delegate.isCancelled()) sink.failed(delegate.session, error)
        }
    }

    private fun answered(id: String, delegate: NativeGrpcDelegate, result: JsonObject) {
        if (pending.remove(id, delegate)) {
            delegate.span?.outcome(answerOutcome(result))
            sink.settled(delegate.session, delegate.cell, delegate.id)
            releaseTrace(id, delegate)
        }
    }

    private fun answerOutcome(result: JsonObject): String =
        if (JsonScalars.str(result, "status") == "error") "bad" else "good"

    fun releaseTrace(id: String, delegate: NativeGrpcDelegate) {
        if (!completions.containsKey(delegate)) tracing.invocations.remove(id, delegate.span)
    }

    /** Cancels every completion still running for [session], before its pending delegates are dropped. */
    fun cancelRunning(session: NativeGrpcSession) {
        completions.keys.filter { it.session === session }.forEach(NativeGrpcDelegate::cancel)
    }
}
