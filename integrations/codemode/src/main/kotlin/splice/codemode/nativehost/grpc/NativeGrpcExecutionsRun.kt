package splice.codemode.nativehost.grpc

import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.execution.NativeGrpcExecutionDriver
import splice.codemode.nativehost.grpc.execution.NativeGrpcExecutionRun
import splice.codemode.nativehost.trace.NativeTracing
import splice.core.util.JsonScalars
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Every execute operation's run, from dispatch until its Execute stream ends. [NativeGrpcExecutionDriver] reads
 *  the stream itself. */
internal class NativeGrpcExecutionsRun(
    channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val executions: NativeGrpcExecutions,
    sink: NativeGrpcRunSink,
    private val tracing: NativeTracing = NativeTracing(),
) {
    private val active = ConcurrentHashMap<Long, NativeGrpcExecutionRun>()
    private val driver = NativeGrpcExecutionDriver(channel, scope, executions, sink)

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
            scope.launch(span.coroutineContext) {
                try {
                    driver.drive(run)
                } finally {
                    retire(run)
                }
            }.invokeOnCompletion { span.close() }
        }
    }

    private fun retire(run: NativeGrpcExecutionRun) {
        active.remove(run.operation, run)
        if (run.abandoned) run.owner?.discard()
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
            driver.fail(run, error)
            run.stream?.close()
        }
    }
}
