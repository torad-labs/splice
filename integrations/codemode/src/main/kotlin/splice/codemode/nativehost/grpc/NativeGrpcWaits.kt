package splice.codemode.nativehost.grpc

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.state.NativeGrpcWaitRun
import splice.codemode.nativehost.grpc.state.NativeGrpcWaitSlots
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One Wait per observed operation: it holds its cell's slot while the host waits, and its outcome is emitted
 *  once. Cancelling or yielding a live wait goes to [NativeGrpcWaitInterrupts]. */
internal class NativeGrpcWaits(
    private val channel: NativeGrpcChannel,
    private val scope: CoroutineScope,
    private val sink: NativeGrpcRunSink,
) {
    private val active = ConcurrentHashMap<Long, NativeGrpcWaitRun>()
    private val slots = NativeGrpcWaitSlots()
    private val interrupts = NativeGrpcWaitInterrupts(channel, scope, sink, active)

    fun start(operation: Long, session: NativeGrpcSession, input: JsonObject, owner: NativeOperation?) {
        val wait = UUID.randomUUID().toString()
        val run = NativeGrpcWaitRun(session, operation, wait, NativeGrpcRequests.wait(session.id, wait, input))
        check(active.putIfAbsent(operation, run) == null) { "Duplicate CodeMode wait operation" }
        val job = scope.launch(start = CoroutineStart.LAZY) { drive(run) }
        owner?.markDispatched()
        run.start(job)
    }

    private suspend fun drive(run: NativeGrpcWaitRun) {
        try {
            if (!prepare(run)) return
            val response = response(run)
            outcome(run, response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: StatusRuntimeException) {
            val fatal = NativeGrpcDeadlines.invalidatesSession(error, run.session.route != null)
            completed(
                run,
                NativeGrpcFrames.failed(run.operation, NativeGrpcErrors.render(error, "wait"), preserveCell = !fatal),
            )
            if (fatal) sink.invalidate(run.session, error)
        } catch (error: NativeGrpcProtocolException) {
            completed(
                run,
                NativeGrpcFrames.failed(run.operation, error.diagnostic, preserveCell = !error.invalidatesSession),
            )
            if (error.invalidatesSession) sink.invalidate(run.session, error)
        } catch (error: IOException) {
            completed(run, NativeGrpcFrames.failed(run.operation, NativeGrpcErrors.render(error, "wait")))
            sink.invalidate(run.session, error)
        } catch (error: IllegalArgumentException) {
            completed(run, NativeGrpcFrames.failed(run.operation, NativeGrpcErrors.render(error, "wait")))
            sink.invalidate(run.session, error)
        } finally {
            release(run)
        }
    }

    private fun release(run: NativeGrpcWaitRun) {
        if (!run.retiring) {
            run.claim?.release()
            active.remove(run.operation, run)
        }
    }

    private suspend fun prepare(run: NativeGrpcWaitRun): Boolean {
        val claim = slots.claim(run.session, NativeGrpcFields.text(run.request, "cell_id"))
        val admitted = synchronized(run) {
            if (run.retiring || run.finished) {
                false
            } else {
                run.claim = claim
                true
            }
        }
        if (!admitted) {
            claim.release()
            return false
        }
        claim.acquire()
        val observing = synchronized(run) {
            if (run.retiring || run.finished) {
                false
            } else {
                run.waiting = true
                true
            }
        }
        if (observing && run.yieldRequested) scope.launch { interrupts.yieldObservation(run.operation) }
        return observing
    }

    private suspend fun response(run: NativeGrpcWaitRun): NativeGrpcReply = try {
        NativeGrpcDeadlines.observation(run.request) {
            channel.unary(NativeGrpcRequest("Wait", run.request, run.session.route, null))
        }
    } finally {
        synchronized(run) {
            if (!run.retiring) {
                run.waiting = false
                run.claim?.release()
            }
        }
    }

    private suspend fun outcome(run: NativeGrpcWaitRun, response: NativeGrpcReply) {
        val value = NativeGrpcResponses.wait(response)
        val live = NativeGrpcFields.optional(response.message, "live_cell")
        val outcome = live ?: NativeGrpcFields.message(response.message, "missing_cell")
        val cell = NativeGrpcFields.identifier(outcome, "cell_id")
        val expected = NativeGrpcFields.text(run.request, "cell_id")
        if (cell != expected) {
            throw NativeGrpcProtocolException(
                "gRPC code-mode host returned cell $cell instead of $expected",
                invalidatesSession = true,
            )
        }
        if (NativeGrpcFields.optional(outcome, "yielded") == null) {
            sink.settle(run.session, cell, NativeGrpcFields.optional(outcome, "terminated") != null)
        }
        completed(run, NativeGrpcFrames.response(run.operation, value))
    }

    private fun completed(run: NativeGrpcWaitRun, frame: JsonObject) {
        synchronized(run) {
            if (run.finished) return
            run.response = frame
            if (!run.retiring) {
                run.finished = true
                sink.emit(frame)
            }
        }
    }

    suspend fun cancel(operation: Long) = interrupts.cancel(operation)

    fun cancelObservation(operation: Long) = interrupts.cancelObservation(operation)

    suspend fun yieldObservation(operation: Long): Boolean = interrupts.yieldObservation(operation)

    fun failed(session: NativeGrpcSession, error: Throwable) {
        active.values.filter { it.session === session }.forEach { run ->
            synchronized(run) {
                if (!run.finished) {
                    run.finished = true
                    sink.emit(NativeGrpcFrames.failed(run.operation, NativeGrpcErrors.render(error, "wait")))
                }
            }
            active.remove(run.operation, run)
            run.cancel()
        }
    }
}
