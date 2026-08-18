// PORT-OF: server/src/codex-proxy.mjs createServer/handleMessages @ pre-public-port-baseline, GENERIC over the
// Provider SPI (the module law keeps concrete dialects out of :gateway). A Ktor Netty embedded
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
package splice.gateway.head

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.spi.Provider

// Wait for in-flight SSE turns to finish (or cancel cleanly) before tearing the engine.
private const val STOP_DRAIN_NS = 5_000_000_000L // 5s
private const val STOP_DRAIN_POLL_MS = 50L

public class HeadServer(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
) : Head {

    private val gate get() = deps.gate
    private val log get() = deps.log

    private val driver = TurnDriver(provider, deps)
    private val window = AdmissionWindow()
    private val responses = AdmissionResponses()
    private val clientAuth = ClientAuth(deps, responses)
    private val bodyReader = RequestBodyReader(deps)
    private val bodyParse = AnthropicBodyParse()
    private val admissionGate = AdmissionGate(provider, deps, window, responses)
    private val diagnostics = HeadDiagnostics(provider, listenPort, deps, driver)
    private val admission = HeadAdmission(
        deps,
        clientAuth,
        admissionGate,
        AdmissionTelemetry(deps.gate, deps.clock),
        TurnPreparation(provider, deps, bodyReader, bodyParse, clientAuth),
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
    private val engine = HeadEngine(provider, listenPort, deps, diagnostics, clientAuth, admission, countTokens)

    private val lifecycle = Mutex()

    override val key: String get() = provider.key
    override val label: String get() = provider.label
    override val port: Int get() = listenPort

    override suspend fun start(): Unit = lifecycle.withLock { startLocked() }

    override suspend fun stop(): Unit = lifecycle.withLock { stopLocked() }

    override suspend fun restart(): Unit = lifecycle.withLock {
        stopLocked()
        startLocked()
    }

    override fun healthSnapshot(): HeadHealth = diagnostics.healthSnapshot(engine.isRunning)

    private fun startLocked() {
        if (engine.isRunning) return
        // G20 contract: a control-plane restart promises a fresh diagnostic baseline; the counters
        // live on the long-lived TurnDriver, so reset them here (review 2026-07-19).
        // restart() is stop-then-start, so this reset alone suffices — a bare stop keeps counters intact.
        driver.resetHealth()
        // NF-01: the 429 cooldown lives on the long-lived UpstreamClient too — restart must be a
        // real escape hatch from an armed horizon, not a no-op the operator discovers mid-outage.
        deps.upstream.clearRateLimitCooldown()
        engine.start()
        window.open()
    }

    private suspend fun stopLocked() {
        // Refuse new turns FIRST so the drain can actually converge, then drain in-flight turns
        // so clients get honest terminals (driveSealingCancellation's cancellation seal) before
        // Netty tears the engine. Bounded wait — never block restart forever.
        window.close()
        val deadlineNs = System.nanoTime() + STOP_DRAIN_NS
        var inflight = gate.snapshot().inflight
        while (inflight > 0 && System.nanoTime() < deadlineNs) {
            deps.waiter.wait(STOP_DRAIN_POLL_MS)
            inflight = gate.snapshot().inflight
        }
        if (inflight > 0) {
            log("[${provider.key}] stop: draining timed out with inflight=$inflight — forcing engine stop\n")
        }
        engine.stop()
        deps.usageStore.flushNow()
    }
}
