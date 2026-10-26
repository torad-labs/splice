package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.StatusRuntimeException
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.state.NativeGrpcAbandonedCells
import splice.codemode.nativehost.trace.NativeTraceSpan
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.JsonScalars
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Acceptance precedes its separately observed outcome; source is never replayed after dispatch. */
internal class NativeGrpcExecutionsRun(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val executions: NativeGrpcExecutions,
    private val sink: NativeGrpcRunSink,
    private val tracing: NativeTracing = NativeTracing(),
) {
    private val active = ConcurrentHashMap<Long, NativeGrpcExecutionRun>()

    fun start(operation: Long, session: NativeGrpcSession, input: JsonObject, owner: NativeOperation?) {
        val execution = UUID.randomUUID().toString()
        val span = tracing.start(
            "code_mode.grpc.execute",
            mapOf(
                "execution.id" to execution,
                "call_id" to JsonScalars.str(input, "tool_call_id").orEmpty(),
            ),
            current = owner?.traceContext ?: Context.current(),
        )
        span.handoff {
            val request = NativeGrpcRequests.execute(session.id, execution, input)
            executions.begin(session, operation, execution, request)
            val run = NativeGrpcExecutionRun(session, operation, execution, request, owner, span)
            check(active.putIfAbsent(operation, run) == null) { "Duplicate CodeMode execute operation" }
            owner?.markDispatched()
            scope.launch(span.coroutineContext) { drive(run) }.invokeOnCompletion { span.close() }
        }
    }

    private suspend fun drive(run: NativeGrpcExecutionRun) {
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
        } finally {
            retire(run)
        }
    }

    private fun retire(run: NativeGrpcExecutionRun) {
        active.remove(run.operation, run)
        if (run.abandoned) run.owner?.discard()
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

    private fun fail(run: NativeGrpcExecutionRun, error: Throwable) {
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

    fun observation(operation: Long): Pair<NativeGrpcSession, String>? = active[operation]?.let {
        it.session to it.execution
    }

    fun abandon(operation: Long) {
        val run = active[operation] ?: return
        synchronized(run) {
            run.abandoned = true
            run.finish()
            run.stream?.close()
        }
    }

    fun failed(session: NativeGrpcSession, error: Throwable) {
        active.values.filter { it.session === session }.forEach { run ->
            fail(run, error)
            run.stream?.close()
        }
    }
}

private class NativeGrpcExecutionRun(
    val session: NativeGrpcSession,
    val operation: Long,
    val execution: String,
    val request: DynamicMessage,
    val owner: NativeOperation?,
    val span: NativeTraceSpan,
) {
    @Volatile var abandoned = false

    @Volatile var activity = "execution"

    @Volatile var accepted = false

    @Volatile var cell = ""

    @Volatile var stream: NativeGrpcStream? = null
    private val finished = AtomicBoolean()

    fun isFinished(): Boolean = finished.get()

    fun finish(): Boolean = finished.compareAndSet(false, true)
}
