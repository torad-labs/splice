// NEW: failure-handling slice 5 (2026-10-27). One turn held in its own request until its cause lifts
// (final-design.md section 9). The active client resumes automatically when a reset, probe or wake releases it.
//
// The hold opens with one line, at once, and the wire then carries a ping every 30 s (ClientChannel's heartbeat), so
// Claude Code reads a live stream. While it holds, the turn gives back its gate slot and spends none of its
// no-progress cap. It continues at the first of: its stated reset, 2 s after and a second later for each turn held
// longer on the same login; a probe the login's schedule sends; another turn's answer on the same login; a quota
// reading turned allowed; a login joining the head; the console's try-now. It ends at the session's wall, 30 s before
// the client's own timeout, at seven days, or at once when the client leaves.
package splice.head.hold

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import splice.core.failure.FailureCause
import splice.core.util.WallClock
import splice.upstream.Waiter
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldEnd
import splice.upstream.retry.HoldWake
import splice.upstream.retry.MAX_HOLD_MS
import splice.upstream.retry.WAKE_STAGGER_MS
import splice.upstream.retry.WAKE_STAGGER_STEP_MS

/** One turn's place among its login's held turns, and the wait that holds it. Lives as long as the turn, so a turn
 *  held again after a probe keeps its place and its seven days. */
internal class HeldTurn(
    private val registry: HoldRegistry,
    private val waiter: Waiter,
    private val wall: WallClock,
) {
    /** The login the turn waits on. */
    var login: String = ""
        private set

    /** When this turn was first held, on the wall clock: its place among the login's held turns. */
    var heldSinceMs: Long = 0L
        private set

    /** What the turn holds for. */
    var cause: FailureCause? = null
        private set

    /** The order the registry first saw this turn in, which breaks a tie between turns held in the same millisecond. */
    var joinedAs: Long = 0L

    /** True while the turn waits; false while it sends, so a 2xx on its login takes it off the held list. */
    @Volatile var waiting: Boolean = false
        private set

    private var woken: CompletableDeferred<Unit>? = null
    private var pending: Signal? = null

    /** A changed probe/rank recomputes the wait without sending; provider and operator wakes may resume it. */
    sealed class Signal {
        data object Changed : Signal()
        data class Wake(val wake: HoldWake) : Signal()
    }

    /** A fresh wake for the wait about to start. Called under the registry's lock. */
    fun arm(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
        woken = it
        pending = null
        waiting = true
    }

    /** Wakes the wait with [wake], answering whether it was waiting. Called under the registry's lock. */
    fun signal(wake: HoldWake): Boolean {
        if (!waiting || pending is Signal.Wake) return false
        pending = Signal.Wake(wake)
        woken?.complete(Unit)
        return true
    }

    /** A predecessor left: recompute the schedule, preserving any actual wake already waiting. */
    fun changed() {
        if (!waiting || pending != null) return
        pending = Signal.Changed
        woken?.complete(Unit)
    }

    fun wakeSignal(): Deferred<Unit> = checkNotNull(woken)

    /** Consume and re-arm in the registry's critical section, so a concurrent wake cannot fall between waits. */
    fun takeSignal(): Signal {
        val signal = checkNotNull(pending)
        arm()
        return signal
    }

    /** Holds as [ask] says until the hold ends; [wallEndMs] is when the session's wall, less its margin, falls on the
     *  wall clock, and [lost] completes when the client leaves a turn that outlives it (a compaction). */
    suspend fun hold(ask: HoldAsk, wallEndMs: Long, lost: Deferred<Unit>): HoldEnd {
        if (login != ask.login) registry.leave(this)
        login = ask.login
        heldSinceMs = ask.heldSinceMs
        cause = ask.cause
        registry.join(this)
        try {
            return waitOut(ask, wallEndMs, lost)
        } finally {
            waiting = false
        }
    }

    private suspend fun waitOut(ask: HoldAsk, wallEndMs: Long, lost: Deferred<Unit>): HoldEnd {
        val maxAt = ask.heldSinceMs + MAX_HOLD_MS
        while (true) {
            val now = wall()
            val end = when {
                now >= wallEndMs -> HoldEnd.WALL
                now >= maxAt -> HoldEnd.MAX_HOLD
                else -> awaitWake(ask, wallEndMs, maxAt, now, lost)
            }
            if (end != null) return end
        }
    }

    private suspend fun awaitWake(
        ask: HoldAsk,
        wallEndMs: Long,
        maxAt: Long,
        now: Long,
        lost: Deferred<Unit>,
    ): HoldEnd? {
        val resetAt = ask.until?.toEpochMilli()?.let {
            it + WAKE_STAGGER_MS + registry.rank(this) * WAKE_STAGGER_STEP_MS
        }
        val probeAt = registry.nextProbeMs(this, ask)
        val next = listOfNotNull(resetAt, probeAt, wallEndMs, maxAt).min()
        return when (race(next - now, registry.waitFor(this), lost)) {
            Woke.Timer -> timerEnded(resetAt, probeAt)
            Woke.ClientGone -> HoldEnd.CLIENT_GONE
            Woke.Signaled -> signaled()
        }
    }

    private fun timerEnded(resetAt: Long?, probeAt: Long?): HoldEnd? {
        val at = wall()
        return when {
            resetAt != null && at >= resetAt -> resumed(at)
            probeAt != null && at >= probeAt -> {
                registry.probed(this, at)
                HoldEnd.RESUME
            }
            else -> null
        }
    }

    private suspend fun signaled(): HoldEnd? {
        val signal = registry.takeSignal(this)
        return if (signal is Signal.Wake) {
            woken(signal.wake).also { if (it == HoldEnd.RESUME) registry.sent(this, wall()) }
        } else {
            null
        }
    }

    /** The turn goes on at [atMs] for its reset: the login's next probe counts from this send. */
    private fun resumed(atMs: Long): HoldEnd {
        registry.sent(this, atMs)
        return HoldEnd.RESUME
    }

    /** What a wake does: a login answered, so the turn goes a second after each turn ahead of it; a probe asked for
     *  goes at once; a login that joined takes the turn. */
    private suspend fun woken(wake: HoldWake): HoldEnd = when (wake) {
        is HoldWake.Served -> {
            waiter.wait((registry.rank(this) + 1) * WAKE_STAGGER_STEP_MS)
            HoldEnd.RESUME
        }
        HoldWake.Allowed, is HoldWake.TryNow -> HoldEnd.RESUME
        HoldWake.Joined -> {
            registry.leave(this)
            HoldEnd.LOGIN_JOINED
        }
    }

    /** What ended one wait. */
    private sealed class Woke {
        data object Timer : Woke()
        data object ClientGone : Woke()
        data object Signaled : Woke()
    }

    /** The first of: [ms] on the head's clock, a wake, the client leaving. */
    private suspend fun race(ms: Long, wake: Deferred<Unit>, lost: Deferred<Unit>): Woke = coroutineScope {
        val timer = async { waiter.wait(ms.coerceAtLeast(0L)) }
        try {
            select {
                wake.onAwait { Woke.Signaled }
                timer.onAwait { Woke.Timer }
                lost.onAwait { Woke.ClientGone }
            }
        } finally {
            timer.cancel()
        }
    }
}
