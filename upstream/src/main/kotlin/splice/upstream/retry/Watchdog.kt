// PORT-OF: server/src/codex/stream.mjs idle-watchdog block @ pre-public-port-baseline — invariants (the v35
// headline fix IS the spec): BEFORE the client has been handed any output the idle limit is
// firstByteTimeout — a big-context prefill (compaction re-reading ~160k tokens) is legitimately
// silent for minutes; reaping prefill on streamIdle caused the abort->retry->cold-re-read loop
// ("compaction ate my quota"). AFTER the first client content frame the limit is streamIdle.
// totalCap bounds the whole turn (an overloaded backend can trickle keepalives forever and leak
// the slot — the "55 inflight, 2 agents" class). Poll interval = min(15s, max(250ms, tier/3)) for
// the tighter of the two idle tiers.
// The fired reason is a TYPED SENTINEL set BEFORE cancelling, so catch sites can tell
// watchdog-fired from client-gone from shutdown.
//
// 2026-09-01 — THE TIER IS THE CLIENT FRAME, NOT THE BYTE. The port flipped tiers on the first
// upstream BYTE (markByte on the raw SSE read / the first WS event). That is the wrong signal for a
// handshake-first protocol: the Responses API answers within 1-5s with response.created /
// response.in_progress — bytes, but no output — and the model then reasons silently over the whole
// prefill. So the v35 spec was defeated by the very stream it protected: the handshake pinned every
// compaction to the short streamIdle tier before a single token existed. Live: 109 codex
// compactions in one day (gpt-5.6-sol, 1.0-1.2MB upstream bodies) died at "no completion within
// the 180s idle cap" with first_byte=1-5s and NO first delta, each failure re-sent cold by the
// client, three sessions looping in parallel. The successful ones that day had first deltas at
// 17s..111s — the same silence, one tier apart. The tier now reads the round's own
// [ClientFrameEmitted] probe (CONTENT_FRAMES_OUT above the round's baseline — the same fact G5
// keys reissue on): until the client has seen content, the silence is prefill/reasoning and the
// first-output cap applies; after it, the stream is mid-output and streamIdle applies. Bytes still
// TOUCH the slot (liveness: a keepalive resets idleness); they no longer choose the limit. A COMPACT
// turn has NO first-output tier (WatchdogBudget.forCompact, wired at TurnDriveFactory) — its wall is
// launchTotalCap alone: the first compaction on the corrected tier still died silent at 300s, and
// raising the tier to totalCap instead of removing it left two pollers flipping a coin over which
// verdict named the stall (gate run 33575037270).
package splice.upstream.retry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.upstream.ClientFrameEmitted
import splice.upstream.NEVER_PINGED_MS
import splice.upstream.Ticker
import splice.upstream.WsPathPulse
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public sealed class WatchdogFired {
    /** [sawClientFrame] names the FAMILY of tier that judged the silence — false: the first-output
     *  tier ([WatchdogBudget.firstByteTimeout]), true: a mid-output tier — and [limitMs] is that
     *  tier's cap, so a terminal message can name the number that fired. V4-116 added a THIRD tier
     *  inside the mid-output family ([WatchdogBudget.stallReanchor], read as a min against
     *  [WatchdogBudget.streamIdle]); a holder that needs to tell the two apart compares [limitMs]
     *  against streamIdle, which is what TurnWatchdog's own hold line does. */
    public data class Idle(val idleMs: Long, val sawClientFrame: Boolean, val limitMs: Long) : WatchdogFired()

    public data class TotalCap(val elapsedMs: Long) : WatchdogFired()
}

/** What proved the round's path alive at the moment a poll held it, because the two transports
 *  prove it with different evidence and a line that named only one of them would be false on the
 *  other. [SERVER_PING] is the WebSocket backend's own heartbeat — the ChatGPT backend pings its
 *  socket every ~20 s whether or not the model has spoken (InboxListener.onPing, probed live), so
 *  for that transport "last ping" is a real, dated event. [OPEN_CONNECTION] is the SSE body: it has
 *  no heartbeat of its own, so the only evidence available is that the upstream connection is still
 *  open and has not errored — which the transport's TCP keepalive turns from an assumption into a
 *  bounded claim, since a peer that stops answering probes has its socket error out within about a
 *  minute. Naming WHICH evidence held a round is what keeps the turn line from reporting a ping on
 *  a transport that has none (V4-125). */
public enum class PathEvidence { SERVER_PING, OPEN_CONNECTION }

/**
 * V4-125 fallback: the OUT-OF-BAND liveness question, asked when the socket itself cannot answer one.
 *
 * Why it is needed at all, given [PathEvidence.OPEN_CONNECTION]: a half-open TCP connection reads
 * exactly like an idle one, so "the body channel is still open" is a claim about what the kernel has
 * told us, not about whether anyone is listening. The preferred answer is TCP keepalive, which makes
 * the kernel find out and error the socket — but that needs a socket-level seam the JDK engine does
 * not expose (JDK-8338681), and the engine that does expose one could not be resolved offline. So the
 * question is asked out of band instead: is this provider reachable from here, right now?
 *
 * WHAT IT PROVES AND WHAT IT DOES NOT, because the difference decides how a false answer should be
 * treated: a probe proves the PATH, never THIS call. A refusal is real evidence that the round cannot
 * succeed — every connection to that provider is failing, so no amount of waiting will produce a
 * token — while a success says nothing about the one connection the round is parked on. Treating the
 * two asymmetrically is deliberate: a probe that cannot be run at all (an exception, a timeout in the
 * probe itself) is INCONCLUSIVE and must not reap anything, or the probe becomes a new way for a
 * healthy turn to die. Only a definite refusal ends a round.
 */
public fun interface ProviderProbe {
    public suspend fun reachable(): Boolean
}

/** The idle poller found the round past its tier and did NOT reap it, because the round's path was
 *  still provably alive (2026-09-06). Recorded once per turn, at the first such poll, so the turn
 *  line can say that the silence was judged and held rather than never noticed: [idleMs] and
 *  [limitMs] are the numbers the poller compared, [pingAgoMs] the age of the liveness that held it,
 *  [sawClientFrame] the tier, and [evidence] the KIND of that liveness.
 *
 *  [pingAgoMs] keeps its name for the WebSocket path, where it dates a real server ping, and reads
 *  as "the liveness reading the pulse returned" on both — [evidence] is what says which. */
public data class WatchdogHeld(
    val idleMs: Long,
    val limitMs: Long,
    val pingAgoMs: Long,
    val sawClientFrame: Boolean,
    /** Defaulted to [PathEvidence.SERVER_PING] so every pre-V4-125 construction site — the WS path,
     *  the tests that pin the WS hold — keeps its exact meaning without being re-argued. */
    val evidence: PathEvidence = PathEvidence.SERVER_PING,
)

public class TurnWatchdog(
    private val budget: WatchdogBudget,
    // Default is monotonic — sleep/wake/NTP must not invent stalls or freeze totalCap.
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    // HD-19: the poll cadence of both loops below. ProcessTicker is `delay(intervalMs); true`, so
    // production paces exactly as it did; a test wires a ticker that returns instantly and can stop
    // the loop after N samples instead of racing a cancellation against a real 250ms..15s sleep.
    private val ticker: Ticker = ProcessTicker(),
    /** Where the one "held on a live path" line goes; the head tags it. No-op by default. */
    private val log: LogSink = LogSink {},
    /** Where an out-of-band [ProviderProbe] runs (V4-125). A SEAM rather than `Dispatchers.IO` at the
     *  call site: the tree injects its dispatchers, and a test that wants to prove the poller is not
     *  blocked by a slow probe needs to replace it. Defaulted so every existing construction site —
     *  production and the whole idle-tier test family — is untouched. */
    private val probeDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
) {
    private val firedRef = AtomicReference<WatchdogFired?>(null)
    private val heldRef = AtomicReference<WatchdogHeld?>(null)
    private val startedAt = clock()

    public val fired: WatchdogFired? get() = firedRef.get()

    /** Set once, at the first poll that held a silent round on a live path; null when no poll did. */
    public val held: WatchdogHeld? get() = heldRef.get()

    /** Round boundary. The idle TIER needs no reset any more — [launchIn] reads the round's own
     *  client-frame probe on every poll, and a fresh round starts from a fresh baseline — but the
     *  DR-7 half stays: a stale IDLE sentinel is cleared. The sentinel is sticky by design so the
     *  terminal decision can name why a round died, but a salvaged round is followed by another
     *  round — and a sentinel left set makes every later round terminate as "stalled" no matter how
     *  healthy it is, and re-vetoes the continuation gates. Only Idle is cleared, and only by CAS:
     *  TotalCap is a WHOLE-TURN verdict that no new round may erase, and a compareAndSet against
     *  the exact observed value cannot race away a fire landing at this instant. */
    public fun resetRound() {
        firedRef.get()?.let { if (it is WatchdogFired.Idle) firedRef.compareAndSet(it, null) }
    }

    /** Paced to the TIGHTER idle tier. All three idle tiers are sampled by the same poller, and a
     *  cap shorter than streamIdle/3 (a test rig, or an operator wanting a fast pre-output verdict)
     *  would otherwise be sampled too late to matter — the rule [launchTotalCap] already applies
     *  against totalCap. Production (180s/300s, and the armed 20s stall tier) still lands on the 15s
     *  ceiling; a stall tier below ~45s is what pulls the cadence down, which is exactly the tier
     *  whose whole purpose is to fire sooner than the others. */
    public fun pollInterval(): Duration {
        val tighter = minOf(budget.streamIdle, budget.firstByteTimeout, budget.stallReanchor)
            .inWholeMilliseconds / IDLE_DIVISOR
        return tighter.coerceIn(MIN_POLL_MS, MAX_POLL_MS).milliseconds
    }

    /**
     * Launch the sibling poller: watches [slot] IDLENESS against the tier [clientFrame] selects, and
     * on breach sets the typed sentinel FIRST, then cancels [target]. Cancel the returned job on
     * clean exit.
     *
     * [clientFrame] is the ROUND's probe (SseRoundDriver baselines CONTENT_FRAMES_OUT per round):
     * false = the client has seen no content this round, so the silence is prefill/reasoning and
     * the first-output cap applies; true = mid-output, so the tighter of streamIdle and the head's
     * armed stall-re-anchor tier applies (V4-116 — a breach of that tier is a round to RESUME, not
     * a turn to end, and the translator reports it with its salvage exactly as a truncation). See the file header for
     * why this is a frame and not a byte. A fold round's BUFFERED final output (held back until the
     * terminal proves the round) and a non-stream turn (no client frames are ever written) read as
     * "no client frame" and so sit on the first-output cap — the lenient side, and literally true:
     * nothing has reached the client yet.
     *
     * DR-7: [target] is a ROUND, not the turn — the SSE path parents a job to the turn job and
     * aborts that round's body channel, so the translator survives to report the stall WITH its
     * salvage. Total elapsed is NOT sampled here any more; see [launchTotalCap].
     *
     * [pathPulse] is the round's SOCKET liveness (2026-09-06), and it is what separates the two
     * things an idle tier cannot tell apart on its own: a model reasoning in silence and a path that
     * died. The ChatGPT backend pings its WebSocket every ~20 s whether or not the model has spoken
     * (InboxListener.onPing, probed live), so a round past its tier whose last ping is within
     * [PATH_PING_GRACE_MS] is HELD — polled on, not reaped — and only a round whose path has also
     * gone quiet is reaped. The live day that earned this (2026-09-05): eleven gpt-6-astra and
     * gpt-5.6-sol turns were reaped at exactly the 300 s first-output tier, with the socket's last
     * server ping 5-20 s old on every close line, and each re-POSTed cold from the client; the
     * model's own answers on that head take 300-750 s of silence before the first delta. Idle is a
     * stall detector, not a budget (operator, DR-7): a held round is still walled by
     * [launchTotalCap].
     *
     * V4-125 — EVERY TRANSPORT PASSES A PULSE NOW, and this paragraph used to end by saying the SSE
     * path passed none and judged exactly as before. That was the defect: with no pulse the default
     * reading is [NEVER_PINGED_MS], so every SSE round past its tier was reaped by construction, and
     * the idle tier was a verdict on a transport that had no way to answer it. An SSE body carries no
     * heartbeat, so its evidence is [PathEvidence.OPEN_CONNECTION] — the connection is still open and
     * has not errored — which the transport's TCP keepalive makes a bounded claim rather than an
     * assumption: a peer that stops answering probes has its socket error out in about a minute, and
     * that read error is what ends the round (as a tear the re-anchor machinery already owns), never
     * this tier. The tier asks; the path answers; only a path that cannot answer is reaped.
     */
    public fun launchIn(
        scope: CoroutineScope,
        slot: InflightGate.Slot,
        target: Job,
        clientFrame: ClientFrameEmitted,
        pathPulse: WsPathPulse = WsPathPulse { NEVER_PINGED_MS },
        /** V4-125: WHICH evidence [pathPulse] is reporting, so a hold can be logged and recorded
         *  without claiming a server ping on a transport that has none. Defaults to
         *  [PathEvidence.SERVER_PING] because that is what the WebSocket path passes and what every
         *  caller meant before this parameter existed. */
        evidence: PathEvidence = PathEvidence.SERVER_PING,
        /** V4-125 fallback: asked ONLY when the pulse says the path is alive, and only for a tier that
         *  is a verdict rather than a heal. It must AGREE before the round is held, so a probe that
         *  refuses ends the round and a probe that is absent leaves the old behaviour untouched —
         *  which is what the WebSocket path, whose pings are real evidence, keeps. */
        probe: ProviderProbe? = null,
    ): Job =
        scope.launch {
            while (isActive) {
                // pollInterval() is coerced to 250ms..15s, so inWholeMilliseconds is the exact value
                // delay(Duration) would have used (its <1ms coerceAtLeast(1) rounding is unreachable here).
                if (!ticker.awaitTick(pollInterval().inWholeMilliseconds)) return@launch
                val idle = slot.idleForMs()
                val seen = clientFrame()
                // V4-116: mid-output the tier is the STALL-RE-ANCHOR one when the head armed it —
                // min, so a head whose own streamIdle is tighter still gets the tighter of the two,
                // and an unarmed head (INFINITE) reads exactly budget.streamIdle as before.
                val idleLimit = if (seen) {
                    minOf(budget.streamIdle, budget.stallReanchor).inWholeMilliseconds
                } else {
                    budget.firstByteTimeout.inWholeMilliseconds
                }
                // DR-7: totalCap is NOT sampled here any more. [launchTotalCap] owns the only
                // whole-turn cancel, and it targets the turn job; this poller now targets a single
                // ROUND, so raising a TotalCap verdict from here would reap one round and let the
                // fold loop open the next — spending past the cap under a name that means "stop".
                // One breach kind per poller, each cancelling the scope it actually owns.
                if (idle >= idleLimit) {
                    // V4-125: THE STALL TIER IS A HEAL, NOT A VERDICT, so the path pulse must NOT
                    // gate it. A breach of [WatchdogBudget.stallReanchor] means "cancel this round
                    // and resume it from the salvage" — a repair the client never sees — so it is
                    // asked and answered without reference to whether the socket is alive. Letting
                    // the pulse hold here would silently disable V4-116 on every transport that
                    // reports a live path, which is precisely the regression this guard exists to
                    // prevent (three MidStreamTearContinuesTest cases caught it on the SSE path).
                    // [stallReanchor] is INFINITE when a head has not armed it, and then
                    // min(streamIdle, INFINITE) is streamIdle, so this reads false and the tier that
                    // fired is an ordinary idle one that the pulse does judge.
                    val stallTier = seen &&
                        budget.stallReanchor.inWholeMilliseconds <= budget.streamIdle.inWholeMilliseconds
                    val pingAgo = pathPulse.lastPingAgoMs()
                    if (holdOnLivePath(stallTier, pingAgo, probe)) {
                        hold(idle, idleLimit, pingAgo, seen, evidence)
                        continue
                    }
                    firedRef.compareAndSet(null, WatchdogFired.Idle(idle, seen, idleLimit))
                    target.cancel()
                    return@launch
                }
            }
        }

    /**
     * V4-125: should this breach HOLD the round rather than reap it?
     *
     * Three questions in order, and each one is a reason to stop asking: the stall tier never holds
     * (it is a heal, not a verdict); a path whose socket reading is already stale is not alive; and
     * the out-of-band probe must agree before the wait is extended.
     *
     * THE PROBE IS ASKED ONLY WHERE ITS ANSWER CAN CHANGE THE OUTCOME. It costs a real connection,
     * and on the stall tier it cannot matter, so asking there would buy nothing and spend a connect
     * on every poll. That is not theoretical: the first cut asked it unconditionally and two
     * MidStreamTearContinuesTest arms went red, because the blocked poller perturbed the timing
     * those V4-116 arms measure.
     */
    private suspend fun holdOnLivePath(stallTier: Boolean, pingAgoMs: Long, probe: ProviderProbe?): Boolean {
        if (stallTier) return false
        if (pingAgoMs > PATH_PING_GRACE_MS) return false
        return probeAgrees(probe)
    }

    /**
     * V4-125: does the out-of-band probe agree that the path is alive?
     *
     * A probe that is ABSENT agrees, which is what keeps the WebSocket path — whose server pings are
     * real, dated evidence — byte-for-byte as it was. A probe that is present is asked, and the ONE
     * failure mode that must not reap a round is the probe failing to run: a DNS hiccup, a timeout in
     * the probe itself, a provider that rate-limits the probe but not the stream. Those are
     * INCONCLUSIVE, not evidence of death, so they read as agreement. Only a definite refusal — the
     * probe ran and the provider said no — ends the round, because only then is it true that no
     * amount of waiting produces a token.
     */
    private suspend fun probeAgrees(probe: ProviderProbe?): Boolean {
        if (probe == null) return true
        // No catch here ON PURPOSE, and the reason is a pair of walls pulling opposite ways: the
        // cancellation rule wants a rethrow inside a broad catch, and detekt refuses both the broad
        // catch and the instanceof that would satisfy it. The way out is to not need one — by
        // contract [ProviderProbe] ANSWERS (true when reachable OR when it could not tell), and the
        // production probe translates its own failures into that answer where the specific exception
        // types are known. A probe that breaks its side of the contract takes the poller down loudly
        // rather than being swallowed here, and an exception that is NOT a probe failure — the
        // cancellation that stops this poller — propagates for free, which is the whole point.
        //
        // Off the poller's own dispatcher: a probe is a socket connect with a timeout, and one that
        // BLOCKS the poller delays every other sample the watchdog owes the round.
        return withContext(probeDispatcher) { probe.reachable() }
    }

    /** Record the hold once and say so once; every later poll that holds is the same fact. */
    private fun hold(idleMs: Long, limitMs: Long, pingAgoMs: Long, seen: Boolean, evidence: PathEvidence) {
        if (!heldRef.compareAndSet(null, WatchdogHeld(idleMs, limitMs, pingAgoMs, seen, evidence))) return
        // The tier is NAMED, not assumed, for the reason the file header gives about the pre-output
        // cap: a mid-output hold on the armed stall tier is a different machine from one on
        // streamIdle, and a line that called a 20 s tier "mid-output" would hide the very knob an
        // operator lowered this week. A tighter limit than streamIdle is exactly "the stall tier is
        // the one that fired".
        val tier = when {
            !seen -> FIRST_OUTPUT_TIER
            limitMs < budget.streamIdle.inWholeMilliseconds -> "mid-output stall re-anchor"
            else -> MID_OUTPUT_TIER
        }
        // The EVIDENCE is named, not assumed, for the same reason the tier above is: the two
        // transports prove liveness differently, and the line that said "last server ping" for both
        // reported a heartbeat the SSE path does not have (V4-125). The SSE arm deliberately prints
        // NO age: the reading it holds on is "still open", which is not a dated event, and inventing
        // a number for it would be the same false precision in a new place.
        val proof = when (evidence) {
            PathEvidence.SERVER_PING -> "last server ping ${pingAgoMs / MS_PER_S}s ago"
            PathEvidence.OPEN_CONNECTION -> "upstream connection open, no read error"
        }
        log(
            "silent ${idleMs / MS_PER_S}s past the ${limitMs / MS_PER_S}s $tier tier on a live path " +
                "($proof) — holding the round, the whole-turn cap " +
                "(${budget.totalCap.inWholeSeconds}s) is its wall\n",
        )
    }

    /** NF-03: the whole-turn wall clock, armed from admission to terminal, and since DR-7 the ONLY
     *  place a totalCap breach is raised. It was once a second sampler beside [launchIn]'s, which
     *  ran only while an upstream stream was open — so connect, headers-wait, retry backoff,
     *  refresh, and between-round gaps went uncounted and an N-round fold/re-anchor turn got N x
     *  the per-round budget against one totalCap while pinning its gate slot. Idle tiers stay with
     *  [launchIn] (they need the slot, and they reap a round rather than the turn); breach
     *  semantics are identical: the typed sentinel is set FIRST, then [target] is cancelled. */
    public fun launchTotalCap(scope: CoroutineScope, target: Job): Job =
        scope.launch {
            // Paced against totalCap as well as streamIdle: pollInterval() alone is streamIdle/3,
            // so a cap tighter than the idle budget would be sampled too late to matter.
            val capThird = budget.totalCap.inWholeMilliseconds / IDLE_DIVISOR
            val interval = minOf(
                pollInterval().inWholeMilliseconds,
                capThird.coerceIn(MIN_POLL_MS, MAX_POLL_MS),
            ).milliseconds
            while (isActive) {
                // Same coercion floor (250ms) as launchIn, so this is delay(interval)'s exact value.
                if (!ticker.awaitTick(interval.inWholeMilliseconds)) return@launch
                val elapsed = clock() - startedAt
                if (elapsed >= budget.totalCap.inWholeMilliseconds) {
                    firedRef.compareAndSet(null, WatchdogFired.TotalCap(elapsed))
                    target.cancel()
                    return@launch
                }
            }
        }
}

private const val IDLE_DIVISOR = 3
private const val MIN_POLL_MS = 250L
private const val MAX_POLL_MS = 15_000L

// Three missed server pings at the ~20 s cadence: a path that has not pinged for this long is not
// the path the round is waiting on, and the idle verdict stands.
private const val PATH_PING_GRACE_MS = 60_000L

// V4-116 — the single source for the tier names a stalled round can be judged by, and the
// milliseconds-per-second divisor the translators scale their "silent Ns" lines with. PUBLIC
// because both stream translators (:dialects-anthropic, :dialect-openai-chat) import them
// rather than re-declaring the same three file-local constants — the one-spelling-each rule that
// keeps a log line and a client-visible sentence from naming the same tier twice.
public const val FIRST_OUTPUT_TIER: String = "first-output"
public const val MID_OUTPUT_TIER: String = "mid-output"

// why: milliseconds in a second — dividing a millisecond figure by it reads seconds
public const val MS_PER_S: Long = 1000L
