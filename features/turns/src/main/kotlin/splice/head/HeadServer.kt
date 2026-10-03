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
import kotlinx.serialization.json.JsonObject
import splice.core.auth.ClientAuthProvider
import splice.core.auth.ForeignHostLog
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.CompactionBudgets
import splice.core.model.ModelCatalog
import splice.core.turn.TurnMeta
import splice.core.usage.QuotaFull
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.AdmissionTelemetry
import splice.head.admission.AdmissionWindow
import splice.head.admission.HeadAdmission
import splice.head.compaction.CompactionReplay
import splice.head.perf.PerfStats
import splice.head.turn.TurnDriver
import splice.head.turn.TurnPreparation
import splice.http.ingress.HeapIngress
import splice.upstream.Provider

// Wait for in-flight SSE turns to finish (or cancel cleanly) before tearing the engine.
//
// V4-74: 45s, and the reason is a MEASURED turn length rather than a round number — the operator's
// deepseek turns run 7 to 16s, so the old 5s drain could not outlive even a short one and every
// restart cut the turn mid-stream, which Claude Code does NOT retry after content (it prints
// "API Error: Connection lost mid-response"). This is the INNERMOST budget of a five-link ladder;
// see Main.kt's STOP_DEADLINE_MS comment for the whole chain, and DaemonStopBudgetTest for the
// ordering that keeps each link below the next.
private const val STOP_DRAIN_NS = 45_000_000_000L // 45s: above a 16s deepseek turn, inside the ladder
private const val STOP_DRAIN_POLL_MS = 50L

public class HeadServer(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
) : Head {

    private val gate get() = deps.gate
    private val log get() = deps.log

    private val compactionReplay = CompactionReplay(deps.stores.compactionRecordings)
    private val driver = TurnDriver(provider, deps, compactionReplay)
    private val window = AdmissionWindow()
    private val responses = AdmissionResponses()
    private val clientAuth = ClientAuth(
        deps,
        responses,
        ForeignHostLog("the ${provider.key} head", deps.log),
        forwardsOnly = provider.auth is ClientAuthProvider,
    )
    private val bodyReader = RequestBodyReader(deps.policy.requestReadTimeoutMs)
    private val bodyParse = AnthropicBodyParse()
    private val admissionGate = AdmissionGate(
        provider,
        deps,
        window,
        responses,
        CompactionPreflight(provider.catalog, deps.stores.perfStats),
    )
    private val diagnostics = HeadDiagnostics(provider, deps.gate, driver, deps.stores.wireTap)
    private val admission = HeadAdmission(
        deps,
        clientAuth,
        admissionGate,
        AdmissionTelemetry(deps.gate, deps.seams.clock),
        TurnPreparation(provider, deps, bodyReader, bodyParse, clientAuth, compactionReplay),
        responses,
        driver,
    )
    private val countTokens = CountTokens(
        provider,
        deps,
        clientAuth,
        admissionGate,
        bodyReader,
        bodyParse,
        responses,
    )
    private val engine = HeadEngine(
        listenPort,
        { line -> deps.log("[${provider.key}] $line") },
        diagnostics,
        clientAuth,
        admission,
        countTokens,
        HeapIngress(
            deps.seams.requestMaterializationGate.heap,
            deps.policy.maxRequestBytes.toLong(),
            deps.seams.requestMaterializationGate.limitBytes,
        ),
    )

    private val lifecycle = Mutex()

    override val key: String get() = provider.key
    override val label: String get() = provider.label

    /** The port this head listens on: the one its connector BOUND while running (the OS-assigned
     *  port when [listenPort] is 0, which is how a test gets a port with no lease-then-bind window),
     *  and the configured [listenPort] while stopped. */
    override val port: Int get() = engine.port

    override suspend fun start(): Unit = lifecycle.withLock { startLocked() }

    override suspend fun stop(): Unit = lifecycle.withLock { stopLocked() }

    override suspend fun restart(): Unit = lifecycle.withLock {
        stopLocked()
        startLocked()
    }

    override fun healthSnapshot(): HeadHealth = diagnostics.healthSnapshot(engine.isRunning, engine.port)

    override fun rateLimitSnapshot(): splice.core.head.RateLimitHealth = diagnostics.rateLimitSnapshot()

    /** The refusal this head holds (V4-398/V4-412), and nothing else: a full reading is [quotaFull] (V4-452). */
    override fun providerResetForMs(): Long =
        deps.quotaBundle.accountPool?.providerResetForMs ?: deps.upstream.providerResetForMs

    /** The provider's own current reading (V4-418, renamed V4-452). Reporting only: nothing here reaches
     *  admission, which still lets the first turn probe the upstream (V4-47). */
    override fun quotaFull(): QuotaFull? = deps.turnQuota.full()

    private suspend fun startLocked() {
        if (engine.isRunning) return
        // G20 contract: a control-plane restart promises a fresh diagnostic baseline; the counters
        // live on the long-lived TurnDriver, so reset them here (review 2026-07-19).
        // restart() is stop-then-start, so this reset alone suffices — a bare stop keeps counters intact.
        driver.resetHealth()
        // NF-01: restart clears whichever cooldown authority the turn path actually uses. Pooled
        // turns bypass the client-owned legacy cooldown, so reset every account instead.
        deps.quotaBundle.accountPool?.reset() ?: deps.upstream.clearRateLimitCooldown()
        engine.start()
        window.open()
        deps.seams.events.lifecycle(HeadLifecycle.STARTED)
    }

    private suspend fun stopLocked() {
        // Refuse new turns FIRST so the drain can actually converge, then drain in-flight turns
        // so clients get honest terminals (driveSealingCancellation's cancellation seal) before
        // Netty tears the engine. Bounded wait — never block restart forever.
        window.close()
        // V4-134: reported only when there was something running to drain, so a stop of a head
        // that never started does not tell the console it went down.
        val wasRunning = engine.isRunning
        if (wasRunning) deps.seams.events.lifecycle(HeadLifecycle.DRAINING)
        val deadlineNs = System.nanoTime() + STOP_DRAIN_NS
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
        driver.stopDetached()
        engine.stop()
        provider.onHeadStop()
        deps.stores.usageStore.flushNow()
        deps.stores.economicsStore?.flushNow()
        deps.stores.perfStats.totals?.flushNow()
        if (wasRunning) deps.seams.events.lifecycle(HeadLifecycle.STOPPED)
    }
}

/** The client compacts reactively only when a size refusal is HTTP 400 before SSE commits 200.
 * A refusal needs a preserved, backend-measured prefix and a text-only growth bound. Cold, rewritten
 * or media-bearing histories go upstream; bytes/3 cannot justify blocking a real conversation. */
internal class CompactionPreflight(private val catalog: ModelCatalog, private val perf: PerfStats) {
    fun refusal(meta: TurnMeta, request: JsonObject, hasPriorExchange: Boolean): String? {
        val budget = CompactionBudgets.forRow(catalog, meta.originalModel) ?: return null
        val window = budget.serveWindow
        val estimate = perf.measuredInputs.estimate(meta.sessionId, meta.conversationKey, meta.upstreamModel, request)
            ?: return null
        // An ordinary continuation may compact early, so use the conservative upper bound.
        // Compact and first-exchange refusals have no recovery behind them: only measured
        // input alone beyond W proves overflow. Generation p99 is not a required minimum.
        val ordinary = hasPriorExchange && !meta.compact
        val allowance = if (ordinary) budget.totalTokens else 0L
        val bound = if (ordinary) estimate.upperTokens else estimate.lowerTokens
        val explanation = if (ordinary) {
            "estimated $bound input tokens plus $allowance reserved context tokens " +
                "exceed the $window-token window of ${meta.upstreamModel} " +
                "(estimate basis ${estimate.basis}, compaction generation p99 ${budget.generationTokens})"
        } else {
            val request = if (meta.compact) "This compaction" else "This first request"
            "at least $bound input tokens exceed the $window-token window of ${meta.upstreamModel}. " +
                "$request cannot fit here. Resume on a model with a larger context window, " +
                "or start a fresh conversation."
        }
        return if (bound > window - allowance) "prompt is too long: $explanation" else null
    }
}
