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

import splice.core.auth.ClientAuthProvider
import splice.core.auth.ForeignHostLog
import splice.core.failure.HeadVoice
import splice.core.failure.VoiceFacts
import splice.core.head.CompactionHold
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.head.HeldSayings
import splice.core.head.HeldTurnReading
import splice.core.topology.ForwardHeaders
import splice.core.usage.QuotaFull
import splice.core.util.HeadScopedLogs
import splice.head.admission.AdmissionErrorBody
import splice.head.lifecycle.HeadLifecycle
import splice.head.turn.TurnState
import splice.http.ingress.HeapIngress
import splice.http.listen.AdoptedBootstrap
import splice.upstream.Provider

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
    /** The caller's headers its provider's forward_headers names, which ride upstream on every turn (Nov 14, 2026). */
    forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
) : Head {

    /** Everything a provider's turn needs, which a gateway's provider runtime builds too (TurnService). The turn path
     *  reads the call's headers through [CallerHeaders], the same forwarding the door below decides on, so what the door
     *  lets in and what rides upstream cannot disagree. */
    private val turns = TurnService(provider, deps, voiceFacts, CallerHeaders(deps, forwarded))

    internal val compactionReplay get() = turns.compactionReplay

    /** Slice 2: every line this head writes a person comes from here (one voice). */
    internal val voice: HeadVoice get() = turns.voice

    /** What this head alone owns beside the shared turn service: its legacy door. */
    private val clientAuth = ClientAuth(
        deps,
        turns.responses,
        ForeignHostLog("the ${provider.key} head", deps.log),
        forwardsOnly = provider.auth is ClientAuthProvider,
        // Under the head's key, so `splice logs --head` and the console's per-head log show which head refused whom.
        refusedKeys = RefusedKeyLog(HeadScopedLogs.headScopedLog(provider.key, deps.log), deps.seams.clock),
        forwarded = forwarded,
    )
    private val engine = HeadEngine(
        HeadListen(listenPort, inherited),
        { line -> deps.log("[${provider.key}] $line") },
        turns.diagnostics,
        clientAuth,
        turns.admission,
        turns.countTokens,
        HeapIngress(
            deps.seams.requestMaterializationGate.heap,
            deps.policy.maxRequestBytes,
            AdmissionErrorBody(voice),
            deps.seams.requestMaterializationGate.limitBytes,
        ),
    )

    private val lifecycle = HeadLifecycle(provider, deps, engine, turns)

    override val key: String get() = provider.key
    override val label: String get() = provider.label
    override val handsOver: Boolean get() = provider.finishesTurnsWhenRetired

    /** The port this head listens on: the one its connector BOUND while running (the OS-assigned
     *  port when [listenPort] is 0, which is how a test gets a port with no lease-then-bind window),
     *  and the configured [listenPort] while stopped. */
    override val port: Int get() = engine.port

    override suspend fun start(): Unit = lifecycle.start()

    override suspend fun drain(): Unit = lifecycle.drain()

    override val compactionHold: CompactionHold = CompactionHold(turns.window::holdCompactions)

    override suspend fun stop(): Unit = lifecycle.stop()

    override suspend fun retire(): Int = lifecycle.retire()

    override suspend fun finish(withinMs: Long): Unit = lifecycle.finish(withinMs)

    override suspend fun restart(): Unit = lifecycle.restart()

    override fun healthSnapshot(): HeadHealth = turns.diagnostics.healthSnapshot(engine.isRunning, engine.port)

    override fun rateLimitSnapshot(): splice.core.head.RateLimitHealth = turns.diagnostics.rateLimitSnapshot()

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

    /** The refusal this head holds (V4-398/V4-412), and nothing else: a full reading is [quotaFull] (V4-452). A head
     *  with a login of its own holds that login's refusal alone: the file of a token it renewed since (claudex, Nov 5)
     *  is restored at every boot and no send can answer it, so counting it read out of quota for days of served turns.
     *  A head that forwards its callers' logins has no login of its own, and reads every caller's. */
    override fun providerResetForMs(): Long = deps.quotaBundle.activePool?.providerResetForMs
        ?: provider.auth.observedCredentialKey()?.let { deps.traffic.upstream.providerResetForMs(it) }
        ?: deps.traffic.upstream.providerResetForMs

    /** The provider's own current reading (V4-418, renamed V4-452). Reporting only: nothing here reaches
     *  admission, which still lets the first turn probe the upstream (V4-47). */
    override fun quotaFull(): QuotaFull? = deps.turnQuota.full()

    /** The turns this head holds until their cause lifts (failure-handling slice 5), oldest first. */
    override fun heldTurns(): List<HeldTurnReading> = deps.traffic.liveTurns.list()
        .mapNotNull { it.state as? TurnState.Held }
        .sortedBy { it.since }
        .map { HeldTurnReading(it.until?.epochSecond, it.login, it.cause, it.heapBytes) }

    /** What each turn this head holds says now, a capability's or a usage limit's (docs/specs/harness.md, 7 and 8). */
    override val heldSayings: HeldSayings = HeldSayings { deps.traffic.liveTurns.sayings() }

    /** Degraded while it refuses every request before any upstream turn (2026-11-09 audit), null while it admits. */
    override fun refusing(): splice.core.head.RefusingReading? = turns.admission.refusing()
}
