package splice.codemode.nativehost.grpc

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.codemode.nativehost.grpc.state.NativeGrpcWaitRun
import splice.core.util.Cancellables
import java.util.concurrent.ConcurrentHashMap

/** The two ways a live wait is cut short. CancelWait retires it, and retiring includes the host's acknowledgement
 *  before a replacement is released. YieldObservation asks it to answer with what it has. */
internal class NativeGrpcWaitInterrupts(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val sink: NativeGrpcRunSink,
    private val active: ConcurrentHashMap<Long, NativeGrpcWaitRun>,
) {
    suspend fun cancel(operation: Long) {
        val run = beginRetirement(operation) ?: return
        finishRetirement(run)
    }

    fun cancelObservation(operation: Long) {
        val run = beginRetirement(operation) ?: return
        scope.launch { finishRetirement(run) }
    }

    private fun beginRetirement(operation: Long): NativeGrpcWaitRun? {
        val run = active[operation] ?: return null
        synchronized(run) {
            if (run.finished || run.retiring) return null
            run.retiring = true
            run.claim?.deactivate()
        }
        return run
    }

    private suspend fun finishRetirement(run: NativeGrpcWaitRun) {
        try {
            run.cancelAndJoin()
            if (run.waiting) {
                NativeGrpcDeadlines.startup("wait cancellation") {
                    channel.unary(
                        NativeGrpcRequest(
                            "CancelWait",
                            NativeGrpcWire.message(
                                "CancelWaitRequest",
                                mapOf("session_id" to run.session.id, "wait_id" to run.wait),
                            ),
                            run.session.route,
                            null,
                        ),
                    )
                }
            }
            synchronized(run) {
                if (!run.finished) {
                    run.finished = true
                    sink.emit(run.response ?: NativeGrpcFrames.failed(run.operation, "CodeMode wait cancelled"))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: StatusRuntimeException) {
            failedRetirement(run, error)
        } catch (error: NativeGrpcProtocolException) {
            failedRetirement(run, error)
        } finally {
            run.claim?.release()
            active.remove(run.operation, run)
        }
    }

    private fun failedRetirement(run: NativeGrpcWaitRun, error: Throwable) {
        sink.invalidate(
            run.session,
            NativeGrpcProtocolException(
                "failed to retire canceled gRPC code-mode wait: ${NativeGrpcErrors.render(error, "wait cancellation")}",
                invalidatesSession = true,
            ),
        )
    }

    suspend fun yieldObservation(operation: Long): Boolean {
        val run = active[operation] ?: return false
        val waiting = synchronized(run) {
            if (!run.finished) run.yieldRequested = true
            run.waiting
        }
        if (waiting) {
            Cancellables.runCatchingBestEffort {
                try {
                    NativeGrpcDeadlines.startup("yield observation") {
                        channel.unary(
                            NativeGrpcRequest(
                                "YieldObservation",
                                NativeGrpcWire.message(
                                    "YieldObservationRequest",
                                    mapOf("session_id" to run.session.id, "wait_id" to run.wait),
                                ),
                                run.session.route,
                                null,
                            ),
                        )
                    }
                } catch (error: NativeGrpcProtocolException) {
                    if (error.invalidatesSession) sink.invalidate(run.session, error)
                } catch (error: StatusRuntimeException) {
                    if (NativeGrpcDeadlines.invalidatesRequest(error, run.session.route != null)) {
                        sink.invalidate(run.session, error)
                    }
                }
            }
        }
        return true
    }
}
