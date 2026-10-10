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
import splice.core.memory.HeapLease
import splice.core.perf.OutcomeTag
import splice.head.CompactionPreflight
import splice.head.HeadDeps
import splice.head.turn.Materialized
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
    private val refusals: GateRefusals = GateRefusals { _, _ -> },
) {
    private val gate get() = deps.traffic.gate
    private val log get() = deps.log

    /** False (with a 529 on the wire) while stopLocked drains — clients retry and land post-restart. */
    suspend fun acceptingOrRespond(call: ApplicationCall): Boolean {
        if (window.isOpen) return true
        refusals.refused(OutcomeTag.RESTARTED, call.request.headers[SESSION_HEADER])
        responses.respondAtCapacity(call, "head is stopping; retry")
        return false
    }

    suspend fun acquireSlotOrRespond(call: ApplicationCall): InflightGate.Slot? =
        acquireOrRespond(call, call.request.headers[SESSION_HEADER], beforeRefusal = null)

    suspend fun acquireFreshSlotOrRespond(call: ApplicationCall, beforeRefusal: TurnEnd): InflightGate.Slot? =
        acquireOrRespond(call, null, beforeRefusal)

    private suspend fun acquireOrRespond(
        call: ApplicationCall,
        session: String?,
        beforeRefusal: TurnEnd?,
    ): InflightGate.Slot? {
        // V4-114: the gate ANSWERS with a value now, so this refusal is a compiler-checked `when`
        // branch rather than a `catch` on a name — the 529 on the wire is unchanged, and the
        // window-closed refusal ten lines below has always been spelled as a returned null.
        val slot = when (val admission = gate.acquire(session)) {
            is InflightGate.Admission.Acquired -> admission.slot
            InflightGate.Admission.AtCapacity -> {
                log("[${provider.key}] admission rejected: gateway at capacity (queued=${gate.snapshot().queued})\n")
                beforeRefusal?.ended()
                refusals.refused(OutcomeTag.AT_CAPACITY, session)
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
            refusals.refused(OutcomeTag.RESTARTED, session)
            responses.respondAtCapacity(call, "head is stopping; retry")
            return null
        }
        return slot
    }

    /** For a block that cannot refuse the cap because it never reads the body. Its declared length
     *  is still checked, which is the arm that bounces an oversized declaration before any wait. */
    suspend fun <T : Any> materializeOrRespond(
        call: ApplicationCall,
        how: Materializing = Materializing(),
        block: MaterializedRequest<T>,
    ): T? = materializeBodyOrRespond(call, how) { Materialized.Done(block()) }

    /** For a block that READS the request body, the one thing that can exceed the cap mid-read.
     *  Saying so costs the block a [Materialized] case and buys a compiler-checked refusal: before
     *  V4-440 the read threw RequestBodyTooLarge and this method's catch was its only recovery. */
    suspend fun <T : Any> materializeBodyOrRespond(
        call: ApplicationCall,
        how: Materializing = Materializing(),
        block: MaterializedRequest<Materialized<T>>,
    ): T? = try {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val cap = deps.policy.maxRequestBytes()
        // An oversized DECLARATION keeps its 413 before any admission wait; an unknown length
        // reserves the full head cap.
        if (declared != null && declared > cap) {
            refuseTooLarge(call, cap, how)
        } else {
            answered(call, declared ?: cap.toLong(), how, block)
        }
    } catch (_: TimeoutCancellationException) {
        how.refused()
        responses.respondReadTimeout(call)
        null
    } catch (_: SseSpuriousWakeupException) {
        how.refused()
        // 408, not 400 (DR-20): a torn client body is a connection event the client may retry;
        // BadRequest told Claude Code the request itself was malformed — a non-retryable class.
        responses.respondReadTimeout(call, "request body stream interrupted")
        null
    }

    /** What the materialization answered, rendered. Null is CONTENTION and nothing else
     *  (MaterializedRequest's contract), so the heap refusal is the null arm, and the cap refusal is
     *  a case — the ONE place both cap arms reach the wire, same status and same bytes. */
    private suspend fun <T : Any> answered(
        call: ApplicationCall,
        bytes: Long,
        how: Materializing,
        block: MaterializedRequest<Materialized<T>>,
    ): T? {
        val materialization = deps.seams.requestMaterializationGate
        val reservation = splice.http.ingress.IngressLeases.borrow(call)
        return when (val leased = how.lease(materialization, bytes, reservation, block)) {
            null -> {
                how.refused()
                val want = materialization.requestBytes(bytes)
                responses.respondHeapRefusal(call, want, materialization.limitBytes)
                null
            }
            is Materialized.Done -> leased.value
            is Materialized.TooLarge -> refuseTooLarge(call, leased.limit, how)
        }
    }

    private suspend fun refuseTooLarge(call: ApplicationCall, limit: Int, how: Materializing): Nothing? {
        how.refused()
        responses.respondTooLarge(call, limit)
        return null
    }
}

/**
 * How ONE materialization is admitted: which lease it takes, who keeps the heap charge after the
 * block returns, and what must end before a refusal is written.
 *
 * It owns the fast-fail CHOICE rather than carrying a flag past it — [lease] is the `if` three call
 * sites used to spell as a boolean argument, and keeping it here is what lets the two entry points
 * above stay three parameters wide with every default counted.
 */
internal class Materializing(
    private val fastFail: Boolean = false,
    private val owner: MaterializationOwner? = null,
    private val beforeRefusal: TurnEnd? = null,
) {
    /** count_tokens fast-fails instead of queueing on the shared heap budget; a turn waits. */
    suspend fun <T : Any> lease(
        gate: RequestMaterializationGate,
        bytes: Long,
        reservation: HeapLease?,
        block: MaterializedRequest<Materialized<T>>,
    ): Materialized<T>? =
        if (fastFail) gate.tryWithLease(bytes, reservation, block) else gate.withLease(bytes, owner, reservation, block)

    /** Ends what a refusal must end before its reply is written: the turn it never ran. */
    fun refused() {
        beforeRefusal?.ended()
    }
}
