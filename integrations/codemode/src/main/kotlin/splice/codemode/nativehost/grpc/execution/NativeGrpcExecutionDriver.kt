package splice.codemode.nativehost.grpc.execution

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.codemode.nativehost.grpc.NativeGrpcChannel
import splice.codemode.nativehost.grpc.NativeGrpcDeadlines
import splice.codemode.nativehost.grpc.NativeGrpcErrors
import splice.codemode.nativehost.grpc.NativeGrpcExecutions
import splice.codemode.nativehost.grpc.NativeGrpcFields
import splice.codemode.nativehost.grpc.NativeGrpcFrames
import splice.codemode.nativehost.grpc.NativeGrpcProtocolException
import splice.codemode.nativehost.grpc.NativeGrpcRequest
import splice.codemode.nativehost.grpc.NativeGrpcResponses
import splice.codemode.nativehost.grpc.NativeGrpcRunSink
import splice.codemode.nativehost.grpc.NativeGrpcStream
import splice.codemode.nativehost.grpc.state.NativeGrpcAbandonedCells
import java.io.IOException

/** One execution's Execute stream. Acceptance precedes its separately observed outcome, and the source is never
 *  replayed after dispatch. */
internal class NativeGrpcExecutionDriver(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val executions: NativeGrpcExecutions,
    private val sink: NativeGrpcRunSink,
) {
    suspend fun drive(run: NativeGrpcExecutionRun) {
        try {
            NativeGrpcStream(
                channel,
                NativeGrpcRequest("Execute", run.request, run.session.route, null, run.span.traceparent),
            ).use { stream ->
                run.stream = stream
                NativeGrpcDeadlines.startup("execution") { stream.opened() }
                run.activity = "execution starting event"
                if (admit(run, stream)) {
                    run.activity = "execution outcome"
                    outcome(run, stream)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: StatusRuntimeException) {
            fail(run, error)
            NativeGrpcErrors.invalidate(sink, run.session, error)
        } catch (error: NativeGrpcProtocolException) {
            fail(run, error)
            NativeGrpcErrors.invalidate(sink, run.session, error)
        } catch (error: IOException) {
            fail(run, error)
            sink.invalidate(run.session, error)
        } catch (error: IllegalArgumentException) {
            fail(run, error)
            sink.invalidate(run.session, error)
        } catch (error: IllegalStateException) {
            fail(run, error)
            sink.invalidate(run.session, error)
        }
    }

    private suspend fun admit(run: NativeGrpcExecutionRun, stream: NativeGrpcStream): Boolean {
        val first = NativeGrpcDeadlines.startup("execution starting event") {
            stream.next("gRPC code-mode execution ended before its starting event")
        }
        val started = NativeGrpcFields.message(first, "started")
        if (NativeGrpcFields.identifier(started, "execution_id") != run.execution) {
            throw NativeGrpcProtocolException(
                "gRPC code-mode execution returned ID ${NativeGrpcFields.text(started, "execution_id")} " +
                    "instead of ${run.execution}",
                invalidatesSession = true,
            )
        }
        run.cell = try {
            NativeGrpcFields.identifier(started, "cell_id")
        } catch (error: NativeGrpcProtocolException) {
            throw NativeGrpcProtocolException(error.diagnostic, invalidatesSession = true, cause = error)
        }
        return synchronized(run) {
            if (run.isFinished()) {
                false
            } else {
                executions.started(run.session, run.operation, started)
                run.accepted = true
                true
            }
        }
    }

    private suspend fun outcome(run: NativeGrpcExecutionRun, stream: NativeGrpcStream) {
        val response = NativeGrpcDeadlines.observation(run.request, "execution outcome") {
            stream.next("gRPC code-mode execution omitted its initial outcome")
        }
        if (NativeGrpcFields.optional(response, "started") != null) {
            throw NativeGrpcProtocolException("gRPC code-mode execution returned an unexpected event")
        }
        val outcome = NativeGrpcFields.message(response, "outcome")
        val value = NativeGrpcResponses.outcome(outcome)
        if (NativeGrpcFields.identifier(outcome, "cell_id") != run.cell) {
            throw NativeGrpcProtocolException(
                "gRPC code-mode execution returned cell ${NativeGrpcFields.text(
                    outcome,
                    "cell_id",
                )} instead of ${run.cell}",
                invalidatesSession = true,
            )
        }
        if (NativeGrpcFields.optional(outcome, "yielded") == null) {
            sink.settle(run.session, run.cell, NativeGrpcFields.optional(outcome, "terminated") != null)
        }
        if (run.finish()) {
            sink.emit(NativeGrpcFrames.response(run.operation, value, initial = true))
        }
    }

    fun fail(run: NativeGrpcExecutionRun, error: Throwable) {
        synchronized(run) {
            if (run.finish()) {
                if (!run.accepted) {
                    executions.abandon(run.operation)?.let { abandoned ->
                        scope.launch { NativeGrpcAbandonedCells.terminate(channel, abandoned) }
                    }
                }
                sink.emit(
                    NativeGrpcFrames.failed(
                        run.operation,
                        NativeGrpcErrors.render(error, run.activity),
                        initial = run.accepted,
                    ),
                )
            }
        }
    }
}
