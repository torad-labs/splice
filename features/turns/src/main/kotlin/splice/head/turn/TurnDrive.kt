// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnDrive) @ 86f1411 — invariants
// unchanged: the per-turn collaborators + data the drive needs. Split out (HD-24) because
// TurnDrive is already a package-wide type (WsRoundDriver + DrivePorts read it) — a package-wide
// type should not live inside one consumer. TurnInputs lives in TurnInputs.kt.
package splice.head.turn

import kotlinx.serialization.json.JsonObject
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.core.wire.RateLimitReply
import splice.head.HeadDeps
import splice.head.perf.SourceRowHold
import splice.head.pipeline.TurnPipeline
import splice.head.round.RoundUsage
import splice.head.round.RunnerSignals
import splice.head.transport.TurnAccountHandoff
import splice.head.turn.delivery.CollectPerf
import splice.head.usage.QuotaTracker
import splice.head.wire.ClientChannel
import splice.head.wire.TurnTerminal
import splice.head.wire.TurnTrace
import splice.upstream.RoundInterceptor
import splice.upstream.ToolSearchPolicy
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.RemainingTurnWait
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The per-turn collaborators + data the drive needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
internal data class TurnDrive(
    /** The admission-time inputs this drive was assembled from: the built request (typed, and THE wire — the
     *  round loop serializes `built.requestBody` per round and reasoning-continuation folding extends its `input`
     *  and re-POSTs without re-parsing; DR-168: a pre-serialized String twin used to sit beside it, and
     *  dispatch never read it), the admission slot, the clock origin, the perf and trace of this turn. */
    val inputs: TurnInputs,
    val emitter: TurnTerminal,
    val watchdog: TurnWatchdog,
    val pipeline: TurnPipeline,
    /** Runner liveness gates + the health hook for absorbed round failures (built once in
     *  TurnDriveFactory.assembleDrive; one construction site, the policies never drift). */
    val signals: RunnerSignals,
    /** The client-facing SSE channel: coalesced writer + write mutex + clientGone flag. */
    val channel: ClientChannel,
    val remainingTurnWait: RemainingTurnWait = RemainingTurnWait { Long.MAX_VALUE },
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
            is TurnOutcome.Failure ->
                (outcome.partial?.usage ?: noRequestUsage) + outcome.salvagedUsage
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage
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

    fun interface SourceRoundStarted {
        fun started(job: kotlinx.coroutines.Job)
    }

    /** The upstream request, typed: the wire itself, serialized per round by the round strategy. */
    val requestBody: JsonObject get() = inputs.built.requestBody
    val meta: TurnMeta get() = inputs.built.meta
    val slot: InflightGate.Slot get() = inputs.slot
    val t0: Long get() = inputs.t0

    /** V4-174: the turn's trace when the head's is on; null records nothing. */
    val trace: TurnTrace? get() = inputs.trace
    val perf: TurnPerf get() = inputs.perf

    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String> get() = inputs.built.extraHeaders

    /** The provider's answering policy for THIS turn's deferred tool surface. Null = no deferral
     *  this turn, or the feature is off — the round loop is byte-for-byte unchanged. */
    val toolSearch: ToolSearchPolicy? get() = inputs.built.toolSearch

    /** Optional gateway-local wrapper around each posted round. */
    val roundInterceptor: RoundInterceptor? get() = inputs.built.roundInterceptor

    /** Current login. Only a pre-accept native refusal can replace it within this turn. */
    var account: AccountSelection? = inputs.accountQuota.account
    var quota: QuotaTracker? = inputs.accountQuota.quota

    /** Trace ownership when captured; otherwise minted only when this drive actually posts to a tap. */
    var turnId: String? = trace?.turnId
        private set

    fun sentTurnId(): String = turnId ?: UUID.randomUUID().toString().also { turnId = it }

    private val claims = TurnClaims()
    val collectPerf = CollectPerf()

    /** This turn's row, held while a source round it posted still streams ([splice.upstream.PostingTurnRow]). */
    val sourceRow = SourceRowHold()
    var sourceRoundStarted: SourceRoundStarted? = null

    /** Chooses an HTTP refusal only while the responder still owns an uncommitted status. */
    fun interface RateLimitRelay {
        fun relay(reply: RateLimitReply): Boolean
    }
    var rateLimitRelay: RateLimitRelay? = null
    var upstreamAccepted: splice.upstream.StreamStart? = null
    var accountHandoff: TurnAccountHandoff? = null

    /** Null is a state: a head whose key is gone records no account rather than a label with nothing behind it. */
    var fallbackAccountLabel: String? = "primary"
    var credentialAccountNames: HeadDeps.CredentialAccountNames? = null
    var observedAccountLabel: String? = null
        private set

    /** Refresh and failover replace this request's observation using the headers of each actual attempt, and each
     *  attempt reports its credential's digest as the newest one sent by the head and by this request's session. */
    fun observeAccount(credentials: Credentials, extra: Map<String, String>) {
        val key = CredentialKey.fromHeaders(
            CredentialKey.headers(credentials, extra),
            (credentials as? Credentials.ApiKey)?.header,
        )
        val names = credentialAccountNames
        if (key != null) names?.sent(key, meta.scope.sessionId)
        observedAccountLabel = key?.let { names?.forCredential(it) }
    }

    /** The model the upstream is asked for: the meta's, read rather than copied. */
    val upstreamModel: String get() = meta.route.upstreamModel

    // `internal`, not `private`: TurnDrive is an internal type and TurnDriver (a different class)
    // reads this — a private member would be unreachable. Reads only this drive's own `perf`.
    internal fun perfCounter(key: String): Long = perf.snapshot().counters[key] ?: 0L

    /** Stamps, before the row is written, whether the ending about to be recorded can be retried. */
    internal fun markPermanent(permanent: Boolean) =
        perf.setCount(PerfKeys.FAILURE_PERMANENT, if (permanent) 1L else 0L)

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
    internal fun sessionTag(): String? = meta.scope.sessionId?.take(SESSION_TAG_CHARS)
}

internal const val SESSION_TAG_CHARS = 8
