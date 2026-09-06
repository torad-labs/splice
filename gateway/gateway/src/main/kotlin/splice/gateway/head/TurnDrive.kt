// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnDrive) @ 86f1411 — invariants
// unchanged: the per-turn collaborators + data the drive needs. Split out (HD-24) because
// TurnDrive is already a package-wide type (WsRoundDriver + DrivePorts read it) — a package-wide
// type should not live inside one consumer. TurnInputs lives in TurnInputs.kt.
package splice.gateway.head

import kotlinx.serialization.json.JsonObject
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RoundUsage
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.TurnTerminal
import splice.spi.InflightGate
import splice.spi.RoundInterceptor
import splice.spi.ToolSearchController
import splice.spi.TurnWatchdog
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
    val upstreamModel: String,
    val perf: TurnPerf,
    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String>,
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
) {
    private val rawRoundUsage = AtomicReference<RoundUsage?>(null)
    private val usageStampClaim = AtomicBoolean(false)

    // `internal`, not `private`: TurnDrive is an internal type and TurnDriver (a different class)
    // reads this — a private member would be unreachable. Reads only this drive's own `perf`.
    internal fun perfCounter(key: String): Long = perf.snapshot().counters[key] ?: 0L

    /** Records exactly the usage an intercepted raw post returned before code mode can use it to
     *  assemble another hidden round. Input/cache are cumulative snapshots; output/reasoning accrue. */
    internal fun recordRawRound(outcome: TurnOutcome) {
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
    internal fun rawRoundUsage(): Usage? = rawRoundUsage.get()?.toUsage()

    /** Finish and cancellation share this per-turn claim, so a known prefix cannot be stamped twice. */
    internal fun claimUsageStamp(): Boolean = usageStampClaim.compareAndSet(false, true)

    /** The client session's short tag for log lines and perf rows; null when it sent none. */
    internal fun sessionTag(): String? = meta.sessionId?.take(SESSION_TAG_CHARS)
}

private const val SESSION_TAG_CHARS = 8
