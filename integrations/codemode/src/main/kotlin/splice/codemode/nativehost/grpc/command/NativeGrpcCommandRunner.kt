package splice.codemode.nativehost.grpc.command

import io.grpc.StatusRuntimeException
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.NativeGrpcErrors
import splice.codemode.nativehost.grpc.NativeGrpcFrames
import splice.codemode.nativehost.grpc.NativeGrpcProtocolException
import splice.codemode.nativehost.grpc.NativeGrpcRunSink
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.trace.NativeTraceElement
import java.io.IOException

/** Runs one operation's host command and answers the operation with its result or its failure. The command is
 *  cancelled with its owner's response, and an owner whose response was cancelled is discarded once it ends. */
internal class NativeGrpcCommandRunner(
    private val scope: CoroutineScope,
    private val sink: NativeGrpcRunSink,
) {
    fun launch(
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
}

internal fun interface NativeGrpcCommand {
    suspend fun execute(): JsonObject
}
