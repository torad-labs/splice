// NEW: what proves a silent round's path alive, the socket's evidence and the out-of-band probe, and the hold it records.
package splice.upstream

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What proved the round's path alive at the moment a poll held it, because the two transports
 *  prove it with different evidence and a line that named only one of them would be false on the
 *  other. [SERVER_PING] is the WebSocket backend's own heartbeat — the ChatGPT backend pings its
 *  socket every ~20 s whether or not the model has spoken (InboxListener.onPing, probed live), so
 *  for that transport "last ping" is a real, dated event. [OPEN_CONNECTION] is the SSE body: it has
 *  no heartbeat of its own, so the only evidence available is that the upstream connection is still
 *  open and has not errored — which the transport's TCP keepalive turns from an assumption into a
 *  bounded claim, since a peer that stops answering probes has its socket error out within
 *  KEEPALIVE_DEAD_PEER_S (25 s), while a live peer's kernel answers every probe however long its model
 *  is silent. Naming WHICH evidence held a round is what keeps the turn line from reporting a ping on
 *  a transport that has none (V4-125). */
public enum class PathEvidence { SERVER_PING, OPEN_CONNECTION }

/**
 * V4-125 fallback: the OUT-OF-BAND liveness question, asked when the socket itself cannot answer one.
 *
 * Why it is needed at all, given [PathEvidence.OPEN_CONNECTION]: a half-open TCP connection reads
 * exactly like an idle one, so "the body channel is still open" is a claim about what the kernel has
 * told us, not about whether anyone is listening. The first answer is TCP keepalive, which makes the
 * kernel find out and error the socket: V4-141 moved the engine to OkHttp for its socket seam, and
 * KeepaliveSocketFactory arms every upstream socket to call a silent peer dead within 25 s. This probe
 * is the second, out-of-band answer, for the path keepalive cannot see past (a proxy or edge that
 * still answers for an origin that is gone): is this provider reachable from here, right now?
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

// Three missed server pings at the ~20 s cadence: a path that has not pinged for this long is not
// the path the round is waiting on, and the idle verdict stands.
private const val PATH_PING_GRACE_MS = 60_000L

/**
 * What a round can say about its path being alive: the [pulse] the transport reads off its socket, WHICH
 * [evidence] that pulse is, and the out-of-band [probe] asked when the socket itself cannot answer.
 *
 * V4-125: owns the question "should a breach HOLD the round rather than reap it?" so the watchdog asks
 * [agrees] and does not carry the probe's contract. The pulse defaults to never pinged; the evidence defaults
 * to [PathEvidence.SERVER_PING] because that is what the WebSocket path passes and what every caller meant
 * before it was named. The probe is asked ONLY when the pulse says the path is alive, on
 * any tier, the stall re-anchor included (Oct 12); an absent probe leaves the old behaviour untouched.
 */
public class PathLiveness(
    private val pulse: WsPathPulse = WsPathPulse { NEVER_PINGED_MS },
    public val evidence: PathEvidence = PathEvidence.SERVER_PING,
    private val probe: ProviderProbe? = null,
) {
    internal fun pingAgoMs(): Long = pulse.lastPingAgoMs()

    /** Two questions in order, each a reason to stop asking: a path whose socket reading is stale is not alive,
     *  and the probe must agree before the wait is extended. The probe costs a real connection, so it is
     *  asked only once the pulse already reads alive. */
    internal suspend fun agrees(pingAgoMs: Long, probeDispatcher: CoroutineDispatcher): Boolean {
        if (pingAgoMs > PATH_PING_GRACE_MS) return false
        return probeAgrees(probeDispatcher)
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
    private suspend fun probeAgrees(probeDispatcher: CoroutineDispatcher): Boolean {
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
