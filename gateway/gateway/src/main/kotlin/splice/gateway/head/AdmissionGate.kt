// PORT-OF: splice/gateway/head/HeadServer.kt (acceptingOrRespond, acquireSlotOrRespond,
// materializeOrRespond) @ 1caedd6 — invariants unchanged: the backpressure plane. BOTH reads of
// the admission window live here, the front-door check and the post-acquire re-check that bounces
// a waiter the InflightGate promoted mid-drain (release under NonCancellable, THEN the 529), and
// the materialization lease keeps its fast-fail arm for cheap best-effort endpoints. Split out
// (HD-24) as the file that owns the InflightGate and the materialization lease together.
package splice.gateway.head

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import splice.spi.GatewayAtCapacityException
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.SseSpuriousWakeupException

internal class AdmissionGate(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val window: AdmissionWindow,
    private val responses: AdmissionResponses,
) {
    private val gate get() = deps.gate
    private val log get() = deps.log

    /** False (with a 529 on the wire) while stopLocked drains — clients retry and land post-restart. */
    suspend fun acceptingOrRespond(call: ApplicationCall): Boolean {
        if (window.isOpen) return true
        responses.respondAtCapacity(call, "head is stopping — retry")
        return false
    }

    suspend fun acquireSlotOrRespond(call: ApplicationCall): InflightGate.Slot? {
        val slot = try {
            gate.acquire()
        } catch (_: GatewayAtCapacityException) {
            log("[${provider.key}] admission rejected: gateway at capacity (queued=${gate.snapshot().queued})\n")
            responses.respondAtCapacity(call, "gateway at capacity")
            return null
        }
        // A waiter promoted from the InflightGate queue AFTER stopLocked closed the window must not
        // start an upstream turn the engine stop will kill — bouncing it here (release + 529) lets
        // the drain actually converge, and every admission path through this helper inherits the
        // bounce (queued waiters defeated the drain; review 2026-07-22 round 3).
        if (!window.isOpen) {
            withContext(NonCancellable) { slot.release() }
            responses.respondAtCapacity(call, "head is stopping — retry")
            return null
        }
        return slot
    }

    // fastFail: cheap best-effort endpoints (count_tokens) tryAcquire instead of queueing — a
    // slow-body flood must not camp the process-shared semaphore real turns materialize through
    // (review 2026-07-22); contention gets the 529 retry shape instead of a queue slot.
    suspend fun <T : Any> materializeOrRespond(
        call: ApplicationCall,
        fastFail: Boolean = false,
        block: MaterializedRequest<T>,
    ): T? = try {
        if (fastFail) {
            val leased = deps.requestMaterializationGate.tryWithLease(block)
            if (leased == null) {
                responses.respondAtCapacity(call, "gateway busy — retry")
            }
            leased
        } else {
            deps.requestMaterializationGate.withLease(block)
        }
    } catch (tooLarge: RequestBodyTooLarge) {
        responses.respondTooLarge(call, tooLarge.limit)
        null
    } catch (_: TimeoutCancellationException) {
        responses.respondReadTimeout(call)
        null
    } catch (_: SseSpuriousWakeupException) {
        // 408, not 400 (DR-20): a torn client body is a connection event the client may retry;
        // BadRequest told Claude Code the request itself was malformed — a non-retryable class.
        responses.respondReadTimeout(call, "request body stream interrupted")
        null
    }
}
