// PORT-OF: server/src/codex-proxy.mjs createServer/handleMessages @ pre-public-port-baseline, GENERIC over the
// Provider SPI (the module law keeps concrete dialects out of :daemon-head). A Ktor Netty embedded
// server on loopback per head. Routes: POST /v1/messages EXACTLY (count_tokens gets its own
// cheap handler — the named change: Node forwarded it as a real turn and burned quota), GET
// /v1/models (discovery-wrapped), GET /health {ok,port,version}. SPLIT (2026-07-18, the audit's
// god-file finding): THIS file is the server shell + request ADMISSION (parse → validate →
// classify → build → gate slot); everything per-turn lives in TurnDriver (drive + telemetry).
// Slot release is NonCancellable (leak-safe teardown).
//
// HD-24 (concentration campaign): this file held the shell AND every admission responsibility, and
// imported eleven subsystems to do it. Each responsibility moved to a sibling in this package,
// carrying its splice.* imports with it. What stays is the head's COMPOSITION ROOT and its
// lifecycle: build the collaborators, hold the lifecycle mutex, start/stop/restart, and own the
// stop-drain ordering (close admission FIRST, drain bounded, then tear the engine). See
// HeadDeps.kt, HeadEngine.kt, AdmissionWindow.kt, HeadAdmission.kt, AdmissionGate.kt,
// AdmissionResponses.kt, AdmissionTelemetry.kt, TurnPreparation.kt, AnthropicBodyParse.kt,
// ClientAuth.kt, HeadDiagnostics.kt, CountTokens.kt, RequestBodyReader.kt in this package.
package splice.head

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.auth.ClientAuthProvider
import splice.core.auth.ForeignHostLog
import splice.core.failure.HeadVoice
import splice.core.failure.VoiceFacts
import splice.core.head.CompactionHold
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.usage.QuotaFull
import splice.core.util.HeadScopedLogs
import splice.head.admission.AdmissionErrorBody
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.AdmissionTelemetry
import splice.head.admission.AdmissionWindow
import splice.head.admission.GateRefusals
import splice.head.admission.HeadAdmission
import splice.head.compaction.CompactionReplay
import splice.head.turn.TurnDriver
import splice.head.turn.TurnPreparation
import splice.http.ingress.HeapIngress
import splice.http.listen.AdoptedBootstrap
import splice.upstream.Provider
import java.util.concurrent.TimeUnit

// Wait for in-flight SSE turns to finish (or cancel cleanly) before tearing the engine.
//
// V4-74: 45s, and the reason is a MEASURED turn length rather than a round number — the operator's
// deepseek turns run 7 to 16s, so the old 5s drain could not outlive even a short one and every
// restart cut the turn mid-stream, which Claude Code does NOT retry after content (it prints
// "API Error: Connection lost mid-response"). This is the INNERMOST budget of a five-link ladder;
// see Main.kt's STOP_DEADLINE_MS comment for the whole chain, and DaemonStopBudgetTest for the
// ordering that keeps each link below the next.
private const val STOP_DRAIN_POLL_MS = 50L

// The cut turn's seal AND its reply must land before Netty is torn down: a collect turn writes its 529 only after
// the seal (CollectTurn.respond), and the slot releases after that. 250ms came from a 34ms cut on an idle machine and
// lost the reply under a 21-minute parallel gate run (2026-10-09, HeadServerStopDrainTest [false]). 2s is sized for a
// loaded host and still fits the ladder: 45s drain + 2s seal + 2.5s engine stop < HEAD_STOP_BUDGET_MS (50s).
// Empty heads skip this wait entirely, and a released slot ends it early.
private const val STOP_SEAL_NS = 2_000_000_000L

public class HeadServer(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
    /** The already-listening socket a socket manager handed the daemon for this head, when it did. */
    inherited: AdoptedBootstrap? = null,
    /** What this head's lines name besides the head itself (failure-handling slice 2): the provider a person pays or
     *  signs in with, whether its model server runs here, and the zone a time reads in. The default names no
     *  provider, on the machine's own zone; HeadServerFactory names the provider per head, and a test names a zone so
     *  the line it reads is the line every machine reads. */
    voiceFacts: VoiceFacts = VoiceFacts(),
) : Head {

    private val gate get() = deps.traffic.gate
    private val log get() = deps.log

    internal val compactionReplay = CompactionReplay(
        deps.stores.compaction.compactionRecordings,
        heap = deps.seams.requestMaterializationGate.heap,
    )

    /** Slice 2: every line this head writes a person comes from here (one voice). */
    internal val voice: HeadVoice = HeadVoice(provider.label, provider.loginCommand.ifEmpty { null }, voiceFacts)
    private val driver = TurnDriver(provider, deps, compactionReplay, voice)
    private val window = AdmissionWindow()
    private val responses = AdmissionResponses(voice)
    private val clientAuth = ClientAuth(
        deps,
        responses,
        ForeignHostLog("the ${provider.key} head", deps.log),
        forwardsOnly = provider.auth is ClientAuthProvider,
        // Under the head's key, so `splice logs --head` and the console's per-head log show which head refused whom.
        refusedKeys = RefusedKeyLog(HeadScopedLogs.headScopedLog(provider.key, deps.log), deps.seams.clock),
    )
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
    private val diagnostics = HeadDiagnostics(
        provider,
        deps.traffic.gate,
        driver,
        deps.stores.captures.wireTap,
        deps.traffic.watchdog,
    )
    private val admission = HeadAdmission(
        deps,
        admissionGate,
        AdmissionTelemetry(deps.traffic.gate, deps.seams.clock),
        TurnPreparation(provider, deps, bodyReader, bodyParse, clientAuth, compactionReplay),
        responses,
        driver,
    )
    private val countTokens = CountTokens(
        provider,
        deps,
        admissionGate,
        bodyReader,
        bodyParse,
        responses,
    )
    private val engine = HeadEngine(
        HeadListen(listenPort, inherited),
        { line -> deps.log("[${provider.key}] $line") },
        diagnostics,
        clientAuth,
        admission,
        countTokens,
        HeapIngress(
            deps.seams.requestMaterializationGate.heap,
            deps.policy.maxRequestBytes,
            AdmissionErrorBody(voice),
            deps.seams.requestMaterializationGate.limitBytes,
        ),
    )

    private val lifecycle = Mutex()

    /** What [drainLocked] left behind for [closeLocked]: that the drain is already spent, so a [stop] after a
     *  [drain] closes the engine at once instead of waiting out a second drain budget on turns the first one
     *  already gave up on. */
    private var drained = false

    override val key: String get() = provider.key
    override val label: String get() = provider.label

    /** The port this head listens on: the one its connector BOUND while running (the OS-assigned
     *  port when [listenPort] is 0, which is how a test gets a port with no lease-then-bind window),
     *  and the configured [listenPort] while stopped. */
    override val port: Int get() = engine.port

    override suspend fun start(): Unit = lifecycle.withLock { startLocked() }

    override suspend fun drain(): Unit = lifecycle.withLock { drainLocked() }

    override val compactionHold: CompactionHold = CompactionHold(window::holdCompactions)

    override suspend fun stop(): Unit = lifecycle.withLock { stopLocked() }

    override suspend fun restart(): Unit = lifecycle.withLock {
        stopLocked()
        startLocked()
    }

    override fun healthSnapshot(): HeadHealth = diagnostics.healthSnapshot(engine.isRunning, engine.port)

    override fun rateLimitSnapshot(): splice.core.head.RateLimitHealth = diagnostics.rateLimitSnapshot()

    override fun providerAnswer(): splice.core.head.ProviderAnswer? = deps.stores.usageStore.providerAnswer()

    override fun providerAnswer(account: String?): splice.core.head.ProviderAnswer? {
        val auth = if (account == null) {
            provider.auth
        } else {
            deps.quotaBundle.activePool?.members?.singleOrNull { it.label == account }?.auth
        }
        val scoped = auth?.let(deps.stores.usageStore::providerAnswer)
        return scoped ?: deps.stores.usageStore.unscopedProviderAnswer().takeIf {
            account == null && deps.quotaBundle.activePool == null
        }
    }

    /** Independent sends report through this head's same retained answer and credential-specific hold. */
    public val providerReplies: splice.head.usage.ProviderReplyObserver =
        splice.head.usage.HeadProviderReplies(deps, provider.auth)

    /** A Compare models send borrows this head's budget and stores, so it is weighed and recorded like a request. */
    public val compareSends: splice.head.usage.CompareSends = splice.head.usage.HeadCompareSends(deps)

    /** The refusal this head holds (V4-398/V4-412), and nothing else: a full reading is [quotaFull] (V4-452). */
    override fun providerResetForMs(): Long =
        deps.quotaBundle.activePool?.providerResetForMs ?: deps.traffic.upstream.providerResetForMs

    /** The provider's own current reading (V4-418, renamed V4-452). Reporting only: nothing here reaches
     *  admission, which still lets the first turn probe the upstream (V4-47). */
    override fun quotaFull(): QuotaFull? = deps.turnQuota.full()

    private suspend fun startLocked() {
        if (engine.isRunning) return
        // A head that is up again has no spent drain behind it: the next stop drains afresh.
        drained = false
        // G20 contract: a control-plane restart promises a fresh diagnostic baseline; the counters
        // live on the long-lived TurnDriver, so reset them here (review 2026-07-19).
        // restart() is stop-then-start, so this reset alone suffices — a bare stop keeps counters intact.
        driver.resetHealth()
        driver.headStarted()
        // NF-01: restart clears whichever cooldown authority the turn path actually uses. Pooled
        // turns bypass the client-owned legacy cooldown, so reset every account instead.
        deps.quotaBundle.activePool?.reset() ?: deps.traffic.upstream.clearRateLimitCooldown()
        engine.start()
        window.open()
    }

    private suspend fun stopLocked() {
        drainLocked()
        closeLocked()
    }

    /** The half of a stop that keeps the port LISTENING: new turns are refused (529, which a client
     *  retries) while the running ones finish, so nothing is ever answered with a refused connection
     *  by a head that is still up. Idempotent — a stop after a drain closes without draining twice. */
    private suspend fun drainLocked() {
        if (drained) return
        // Refuse new turns FIRST so the drain can actually converge, then drain in-flight turns
        // so clients get honest terminals (driveSealingCancellation's cancellation seal) before
        // Netty tears the engine. Bounded wait — never block restart forever.
        engine.closeAdmission(window)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deps.policy.stopDrainMs)
        var inflight = gate.snapshot().inflight
        while (inflight > 0 && System.nanoTime() < deadlineNs) {
            deps.seams.waiter.wait(STOP_DRAIN_POLL_MS)
            inflight = gate.snapshot().inflight
        }
        if (inflight > 0) {
            log("[${provider.key}] stop: draining timed out with inflight=$inflight; forcing engine stop\n")
        }
        // A detached compaction OUTLIVES ITS CLIENT (TurnStreamer): its handed-off slot travels with
        // the drive, and the drain budget above belongs to that feature — a detached compaction that
        // finishes inside the budget releases its slot and keeps its recording for the retry. End
        // only what is STILL driving once the budget is spent.
        driver.stopActive()
        driver.stopDetached()
        val sealDeadlineNs = System.nanoTime() + STOP_SEAL_NS
        StopSeal(gate, engine, deps.seams.waiter).settle(sealDeadlineNs)
        drained = true
    }

    /** The half that closes the port and settles this head's books. Everything here is what a client
     *  can no longer be served through, so it runs as late as the daemon's stop can leave it. */
    private fun closeLocked() {
        engine.stop()
        provider.onHeadStop()
        driver.flushHeldRows()
        deps.stores.usageStore.flushNow()
        deps.stores.economicsStore?.flushNow()
        deps.stores.perfStats.totals?.flushNow()
        // Spent: a head that starts again, or is stopped again, drains afresh.
        drained = false
    }
}
