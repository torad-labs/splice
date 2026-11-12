package splice.codemode.nativehost.grpc

import io.grpc.StatusRuntimeException
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.state.NativeGrpcAbandonedCells
import splice.core.util.Cancellables

internal class NativeGrpcControls(
    private val channel: NativeGrpcChannel,
    private val executions: NativeGrpcExecutionsRun,
    private val waits: NativeGrpcWaits,
    private val admission: NativeGrpcExecutions,
    private val sink: NativeGrpcRunSink,
) {
    fun execute(operation: NativeOperation?, id: Long, session: NativeGrpcSession, input: JsonObject) {
        executions.start(id, session, input, operation)
    }

    fun wait(operation: NativeOperation?, id: Long, session: NativeGrpcSession, input: JsonObject) {
        waits.start(id, session, input, operation)
    }

    suspend fun control(type: String, operation: Long) {
        when (type) {
            "operation/observe" -> admission.ready(operation)
            "operation/cancel" -> waits.cancel(operation)
            "operation/yield" -> yield(operation)
            "operation/abandon" -> {
                executions.abandon(operation)
                admission.abandon(operation)?.let { NativeGrpcAbandonedCells.terminate(channel, it) }
            }
            else -> error("Unknown CodeMode observation control")
        }
    }

    private suspend fun yield(operation: Long) {
        if (waits.yieldObservation(operation)) return
        val observing = executions.observation(operation) ?: return
        val (session, id) = observing
        // The official client treats YieldObservation as best effort; the ordinary observation remains live.
        Cancellables.runCatchingBestEffort {
            try {
                NativeGrpcDeadlines.startup("yield observation") {
                    channel.unary(
                        NativeGrpcRequest(
                            "YieldObservation",
                            NativeGrpcWire.message(
                                "YieldObservationRequest",
                                mapOf("session_id" to session.id, "execution_id" to id),
                            ),
                            session.route,
                            null,
                        ),
                    )
                }
            } catch (error: NativeGrpcProtocolException) {
                if (error.invalidatesSession) sink.invalidate(session, error)
            } catch (error: StatusRuntimeException) {
                if (NativeGrpcDeadlines.invalidatesRequest(
                        error,
                        session.route != null,
                    )
                ) {
                    sink.invalidate(session, error)
                }
            }
        }
    }
}
