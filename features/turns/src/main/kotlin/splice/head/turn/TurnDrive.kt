// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnDrive) @ 86f1411 — invariants
// unchanged: the per-turn collaborators + data the drive needs. Split out (HD-24) because
// TurnDrive is already a package-wide type (WsRoundDriver + DrivePorts read it) — a package-wide
// type should not live inside one consumer. TurnInputs lives in TurnInputs.kt.
package splice.head.turn

import kotlinx.serialization.json.JsonObject
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.head.pipeline.TurnPipeline
import splice.head.round.RoundUsage
import splice.head.round.RunnerSignals
import splice.head.usage.QuotaTracker
import splice.head.wire.ClientChannel
import splice.head.wire.TurnTerminal
import splice.head.wire.TurnTrace
import splice.upstream.RoundInterceptor
import splice.upstream.ToolSearchController
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.RemainingTurnWait
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The per-turn collaborators + data the drive needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
internal data class TurnDrive(
    /** The upstream request, typed. This IS the wire: the round loop serializes it per round
     *  (RoundStrategy) and reasoning-continuation folding extends its `input` and re-POSTs without
     *  re-parsing. DR-168: a pre-serialized String twin used to sit beside it — dispatch never read
     *  it, so a test pinning it would have pinned nothing; HeadServerIntegrationTest pins the bytes
     *  the mock upstream decodes instead. */
    val requestBody: JsonObject,
    val meta: TurnMeta,
    val emitter: TurnTerminal,
    val watchdog: TurnWatchdog,
    val slot: InflightGate.Slot,
    val pipeline: TurnPipeline,
    val t0: Long,
    /** V4-174: the turn's trace when the head's is on; null records nothing. Took the slot of the
     *  former `upstreamModel` field, which every construction set to `meta.upstreamModel` and is
     *  now the property below — so the constructor-width ratchet's 17 stays 17. */
    val trace: TurnTrace?,
    val perf: TurnPerf,
    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String>,
    /** Immutable OAuth account chosen before this turn started. */
    val account: AccountSelection? = null,
    /** Runner liveness gates + the health hook for absorbed round failures (built once in
     *  TurnDriveFactory.assembleDrive; one construction site, the policies never drift). */
    val signals: RunnerSignals,
    /** The client-facing SSE channel: coalesced writer + write mutex + clientGone flag. */
    val channel: ClientChannel,
    /** The provider's answering policy for THIS turn's deferred tool surface. Null = no deferral
     *  this turn, or the feature is off — the round loop is byte-for-byte unchanged. */
    val toolSearch: ToolSearchController?,
    /** Optional gateway-local wrapper around each posted round. */
    val roundInterceptor: RoundInterceptor? = null,
    val remainingTurnWait: RemainingTurnWait = RemainingTurnWait { Long.MAX_VALUE },
    val quota: QuotaTracker? = null,
) {
    /** The three per-turn CLAIMS, in the component that claims them (kt-no-atomic-in-data-class).
     *
     *  The rule's harm is value semantics — a `copy()` handing a second TurnDrive the same handle (or
     *  silently dropping it), `equals` comparing handles by reference — and the fix it prescribes is
     *  the owner's shape: whoever calls `compareAndSet` gives the handle a private field and a NAMED
     *  method, and the bundle reaches it through that name. So the atomics stop being members of the
     *  value bundle and become this holder's private state; the drive's public surface is unchanged
     *  (`recordRawRound` / `rawRoundUsage` / `claimUsageStamp` / `claimAccountBoundary` keep their
     *  names and signatures), which is what keeps the change a declaration-and-wiring diff.
     *
     *  NESTED, deliberately: concentration does not bill nested types (NESTED_TYPE_DECL is reported,
     *  not charged), so this costs the file nothing, and the claims cannot drift away from the drive
     *  whose lifecycle they describe. It also cannot re-trip this rule — verified by probe, not
     *  assumed: ast-grep's `has` does not reach into a nested class body. */
    private class TurnClaims {
        private val rawRoundUsage = AtomicReference<RoundUsage?>(null)
        private val usageStampClaim = AtomicBoolean(false)
        private val accountBoundaryClaim = AtomicBoolean(false)

        /** Records exactly the usage an intercepted raw post returned before code mode can use it
         *  to assemble another hidden round. Input/cache are cumulative snapshots; output/reasoning
         *  accrue. The CAS loop is what makes two rounds landing at once merge rather than clobber. */
        fun mergeRawRound(outcome: TurnOutcome) {
            val usage = rawUsageOf(outcome) ?: return
            while (true) {
                val previous = rawRoundUsage.get()
                if (rawRoundUsage.compareAndSet(previous, nextRawRoundUsage(previous, outcome, usage))) return
            }
        }

        private fun rawUsageOf(outcome: TurnOutcome): Usage? = when (outcome) {
            is TurnOutcome.Success -> outcome.usage
            is TurnOutcome.Failure -> outcome.partial?.usage
            is TurnOutcome.ClientAbandoned -> null
        }

        private fun nextRawRoundUsage(previous: RoundUsage?, outcome: TurnOutcome, usage: Usage): RoundUsage {
            val prefix = previous ?: RoundUsage()
            return if (outcome is TurnOutcome.Failure) prefix.plusTerminal(usage) else prefix.plusRound(usage)
        }

        /** The completed-round prefix available when cancellation interrupts a later raw post. */
        fun rawUsage(): Usage? = rawRoundUsage.get()?.toUsage()

        /** Finish and cancellation share this per-turn claim: a known prefix is stamped once. */
        fun claimUsageStamp(): Boolean = usageStampClaim.compareAndSet(false, true)

        /** True once on the first round after an account switch. */
        fun claimAccountBoundary(): Boolean = accountBoundaryClaim.compareAndSet(false, true)
    }

    private val claims = TurnClaims()

    /** The model the upstream is asked for: the meta's, read rather than copied. */
    val upstreamModel: String get() = meta.upstreamModel

    // `internal`, not `private`: TurnDrive is an internal type and TurnDriver (a different class)
    // reads this — a private member would be unreachable. Reads only this drive's own `perf`.
    internal fun perfCounter(key: String): Long = perf.snapshot().counters[key] ?: 0L

    /** Records exactly the usage an intercepted raw post returned before code mode can use it to
     *  assemble another hidden round. Input/cache are cumulative snapshots; output/reasoning accrue. */
    internal fun recordRawRound(outcome: TurnOutcome) {
        claims.mergeRawRound(outcome)
    }

    /** The completed-round prefix available when cancellation interrupts a later raw post. */
    internal fun rawRoundUsage(): Usage? = claims.rawUsage()

    /** Finish and cancellation share this per-turn claim, so a known prefix cannot be stamped twice. */
    internal fun claimUsageStamp(): Boolean = claims.claimUsageStamp()

    /** True once on the first round after an account switch. */
    internal fun claimAccountBoundary(): Boolean = claims.claimAccountBoundary()

    /** The client session's short tag for log lines and perf rows; null when it sent none. */
    internal fun sessionTag(): String? = meta.sessionId?.take(SESSION_TAG_CHARS)
}

internal const val SESSION_TAG_CHARS = 8
