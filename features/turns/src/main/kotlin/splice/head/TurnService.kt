// NEW: gateway-no-heads. The turn-serving assembly both ingress paths instantiate: a head's own listener (HeadServer)
// and the gateway's provider runtime (ProviderRuntime) hold one of these and differ only in what stands in front of
// it. What is here is everything a provider's turn needs and nothing a listener does: admission, the driver, the body
// read and parse, count_tokens, compaction replay, the head's voice and the shared drain. A head owns its port and its
// legacy door (ClientAuth); a provider the gateway serves owns neither, so nothing of either is in this class.
package splice.head

import splice.core.failure.HeadVoice
import splice.core.failure.VoiceFacts
import splice.core.util.WallClockTime
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.AdmissionTelemetry
import splice.head.admission.AdmissionWindow
import splice.head.admission.GateRefusals
import splice.head.admission.HeadAdmission
import splice.head.compaction.CompactionReplay
import splice.head.turn.TurnDriver
import splice.head.turn.TurnPreparation
import splice.upstream.Provider
import splice.upstream.credentials.EarlyResetHeard
import java.util.concurrent.TimeUnit

internal class TurnService(
    val provider: Provider,
    val deps: HeadDeps,
    /** What this provider's lines name besides itself (failure-handling slice 2). The default names no provider. */
    voiceFacts: VoiceFacts,
    /** What of a call rides upstream with its turn: the head's caller headers, or the gateway's (which carry none of the
     *  call's own credential). */
    upstream: UpstreamHeaders,
) {
    val compactionReplay = CompactionReplay(
        deps.stores.compaction.compactionRecordings,
        heap = deps.seams.requestMaterializationGate.heap,
    )

    /** Slice 2: every line this provider writes a person comes from here (one voice). Read on the provider's wall clock,
     *  which its holds wait on, so a hold line names the time its wait ends at. */
    val voice: HeadVoice = HeadVoice(
        provider.label,
        provider.loginCommand.ifEmpty { null },
        voiceFacts,
        WallClockTime(deps.traffic.upstream.retryWall),
    )
    val driver = TurnDriver(provider, deps, compactionReplay, voice)

    init {
        // Failure-handling slice 5: a login joining the provider and a quota reading turned allowed each wake its held turns.
        val holds = deps.traffic.liveTurns.holds
        // The journal says when it happened: on Nov 12, 2026 a login was added while eleven turns waited, and no line
        // told whether any of them had heard it.
        val joins = deps.quotaBundle.accountPool?.joins
        joins?.onJoined = Runnable {
            val waiting = holds.joined()
            if (waiting > 0) deps.log("[${provider.key}] a login joined: $waiting waiting turn(s) asked to move\n")
        }
        // A login reset at its provider comes back at once, and the journal says which one and why.
        joins?.onEarlyReset = EarlyResetHeard { label, ended ->
            val windows = ended.joinToString(" and ")
            deps.log("[${provider.key}] login $label reads $windows reset before its time: its hold ended\n")
        }
        deps.quotaBundle.onAllowed { holds.allowed() }
    }

    val window = AdmissionWindow()
    val responses = AdmissionResponses(voice)
    private val bodyReader = RequestBodyReader(deps.policy.requestReadTimeoutMs)
    private val bodyParse = AnthropicBodyParse()
    private val admissionGate = AdmissionGate(
        provider,
        deps,
        window,
        responses,
        CompactionPreflight(provider.catalog, deps.stores.perfStats),
        GateRefusals(driver::recordGateRefusal),
    )
    val diagnostics = HeadDiagnostics(
        provider,
        deps.traffic.gate,
        driver,
        deps.stores.captures.wireTap,
        deps.traffic.watchdog,
    )
    val admission = HeadAdmission(
        deps,
        admissionGate,
        AdmissionTelemetry(deps.traffic.gate, deps.seams.clock),
        TurnPreparation(provider, deps, bodyReader, bodyParse, upstream, compactionReplay),
        responses,
        driver,
    )
    val countTokens = CountTokens(provider, deps, admissionGate, bodyReader, bodyParse, responses)

    /** The half of a drain that does not depend on how a request arrives. The caller has already refused new turns;
     *  this waits for running ones up to the stop-drain budget, then cuts whatever is still driving with the restart
     *  line, and ends once no turn holds a gate slot and no call is still inside a handler ([activeCalls]), since a
     *  turn's slot is released before its last chunk is written. Refusing new turns first is the caller's, so that a
     *  listener can also stop accepting connections. */
    suspend fun settle(activeCalls: ActiveCalls) {
        val gate = deps.traffic.gate
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deps.policy.stopDrainMs)
        var inflight = gate.snapshot().inflight
        while (inflight > 0 && System.nanoTime() < deadlineNs) {
            deps.seams.waiter.wait(STOP_DRAIN_POLL_MS)
            inflight = gate.snapshot().inflight
        }
        if (inflight > 0) {
            deps.log("[${provider.key}] stop: cutting inflight=$inflight with the restart line; forcing engine stop\n")
        }
        // A detached compaction OUTLIVES ITS CLIENT (TurnStreamer): its handed-off slot travels with the drive. One
        // that finished inside the budget released its slot and kept its recording for the retry. End only what is
        // STILL driving once the budget is spent.
        driver.stopActive()
        driver.stopDetached()
        StopSeal(gate, activeCalls, deps.seams.waiter).settle(System.nanoTime() + STOP_SEAL_NS)
    }

    /** What closing a provider settles once nothing can reach it: its own stop hook, the rows held for a turn that has
     *  not ended, and its books. [retired] (#523): the books are the next daemon's since the hand-over, and writing them
     *  here would put this process's older copy over what the next daemon has counted since. */
    fun close(retired: Boolean) {
        provider.onHeadStop()
        driver.flushHeldRows()
        if (!retired) {
            deps.stores.usageStore.flushNow()
            deps.stores.economicsStore?.flushNow()
            deps.stores.perfStats.totals?.flushNow()
        }
    }
}

private const val STOP_DRAIN_POLL_MS = 50L

// The cut turn's seal AND its reply must land before the listener is torn down: a collect turn writes its 529 only
// after the seal (CollectTurn.respond), and the slot releases after that. 250ms came from a 34ms cut on an idle machine
// and lost the reply under a 21-minute parallel gate run (2026-10-09, HeadServerStopDrainTest [false]). 2s is sized for
// a loaded host and still fits the ladder: drain + 2s seal + 2.5s engine stop < HEAD_STOP_BUDGET_MS (50s). Empty
// heads skip this wait entirely, and a released slot ends it early.
private const val STOP_SEAL_NS = 2_000_000_000L
