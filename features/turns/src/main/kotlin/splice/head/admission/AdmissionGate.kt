// PORT-OF: splice/gateway/head/HeadServer.kt (acceptingOrRespond, acquireSlotOrRespond,
// materializeOrRespond) @ 1caedd6 — invariants unchanged: the backpressure plane. BOTH reads of
// the admission window live here, the front-door check and the post-acquire re-check that bounces
// a waiter the InflightGate promoted mid-drain (release under NonCancellable, THEN the 529), and
// the materialization lease keeps its fast-fail arm for cheap best-effort endpoints. Split out
// (HD-24) as the file that owns the InflightGate and the materialization lease together.
package splice.head.admission

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import splice.head.CompactionPreflight
import splice.head.HeadDeps
import splice.head.RequestBodyTooLarge
import splice.head.turn.MaterializedRequest
import splice.head.turn.SESSION_HEADER
import splice.upstream.Provider
import splice.upstream.TurnEnd
import splice.upstream.failure.SseSpuriousWakeupException
import splice.upstream.retry.InflightGate

internal class AdmissionGate(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val window: AdmissionWindow,
    private val responses: AdmissionResponses,
    internal val compactionPreflight: CompactionPreflight = CompactionPreflight(
        provider.catalog,
        deps.stores.perfStats,
    ),
) {
    private val gate get() = deps.gate
    private val log get() = deps.log

    /** False (with a 529 on the wire) while stopLocked drains — clients retry and land post-restart. */
    suspend fun acceptingOrRespond(call: ApplicationCall): Boolean {
        if (window.isOpen) return true
        responses.respondAtCapacity(call, "head is stopping; retry")
        return false
    }

    suspend fun acquireSlotOrRespond(call: ApplicationCall): InflightGate.Slot? =
        acquireOrRespond(call, gate.resumeSource(call.request.headers[SESSION_HEADER]), beforeRefusal = null)

    suspend fun acquireFreshSlotOrRespond(call: ApplicationCall, beforeRefusal: TurnEnd): InflightGate.Slot? =
        acquireOrRespond(call, null, beforeRefusal)

    private suspend fun acquireOrRespond(
        call: ApplicationCall,
        held: InflightGate.Slot?,
        beforeRefusal: TurnEnd?,
    ): InflightGate.Slot? {
        // V4-114: the gate ANSWERS with a value now, so this refusal is a compiler-checked `when`
        // branch rather than a `catch` on a name — the 529 on the wire is unchanged, and the
        // window-closed refusal ten lines below has always been spelled as a returned null.
        val slot = held ?: when (val admission = gate.acquire()) {
            is InflightGate.Admission.Acquired -> admission.slot
            InflightGate.Admission.AtCapacity -> {
                log("[${provider.key}] admission rejected: gateway at capacity (queued=${gate.snapshot().queued})\n")
                beforeRefusal?.ended()
                responses.respondAtCapacity(call, "gateway at capacity")
                return null
            }
        }
        // A waiter promoted from the InflightGate queue AFTER stopLocked closed the window must not
        // start an upstream turn the engine stop will kill — bouncing it here (release + 529) lets
        // the drain actually converge, and every admission path through this helper inherits the
        // bounce (queued waiters defeated the drain; review 2026-07-22 round 3).
        if (!window.isOpen) {
            withContext(NonCancellable) { slot.release() }
            beforeRefusal?.ended()
            responses.respondAtCapacity(call, "head is stopping; retry")
            return null
        }
        return slot
    }

    // count_tokens fast-fails instead of queueing on the shared heap budget. Unknown body lengths
    // reserve the full head cap; oversized declarations keep their 413 before any admission wait.
    suspend fun <T : Any> materializeOrRespond(
        call: ApplicationCall,
        fastFail: Boolean = false,
        owner: MaterializationOwner? = null,
        beforeRefusal: TurnEnd? = null,
        block: MaterializedRequest<T>,
    ): T? = try {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val cap = deps.policy.maxRequestBytes
        if (declared != null && declared > cap) throw RequestBodyTooLarge(cap)
        val bytes = declared ?: cap.toLong()
        val materialization = deps.seams.requestMaterializationGate
        val leased = if (fastFail) {
            materialization.tryWithLease(bytes, block = block)
        } else {
            materialization.withLease(bytes, owner, block = block)
        }
        if (leased == null) {
            beforeRefusal?.ended()
            responses.respondHeapRefusal(call, materialization.requestBytes(bytes), materialization.limitBytes)
        }
        leased
    } catch (tooLarge: RequestBodyTooLarge) {
        beforeRefusal?.ended()
        responses.respondTooLarge(call, tooLarge.limit)
        null
    } catch (_: TimeoutCancellationException) {
        beforeRefusal?.ended()
        responses.respondReadTimeout(call)
        null
    } catch (_: SseSpuriousWakeupException) {
        beforeRefusal?.ended()
        // 408, not 400 (DR-20): a torn client body is a connection event the client may retry;
        // BadRequest told Claude Code the request itself was malformed — a non-retryable class.
        responses.respondReadTimeout(call, "request body stream interrupted")
        null
    }
}
