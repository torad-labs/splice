// NEW: failure-handling slice 5 (2026-10-27). A head's held turns, by the login each waits on (final-design.md section
// 9, "What wakes it, whichever comes first"). One probe schedule per login: the turn held longest on it probes, and the
// others wait for its answer, so ten turns held on one spent window send one probe, not ten. A probe served, another
// turn's answer on the login, a quota reading turned allowed, a login joining the head and the console's try-now each
// wake the held turns before their timer.
package splice.head.hold

import kotlinx.coroutines.Deferred
import splice.core.failure.FailureCause
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldWake
import splice.upstream.retry.HoldWakes
import splice.upstream.retry.ProbeSchedule
import splice.upstream.retry.Probes

/** The held turns of one head, and the signals that wake them. */
public class HoldRegistry : HoldWakes {
    /** One login's held turns, oldest first, and its one probe schedule: [probes] sent so far, the last at [lastProbeMs]
     *  on the wall clock. */
    private class LoginHolds {
        val turns = mutableListOf<HeldTurn>()
        var probes = 0
        var lastProbeMs: Long? = null
    }

    private val lock = Any()
    private val byLogin = LinkedHashMap<String, LoginHolds>()
    private var joined = 0L

    override fun wake(wake: HoldWake) {
        when (wake) {
            is HoldWake.Served -> served(wake.login)
            HoldWake.Allowed -> allowed()
            HoldWake.Joined -> joined()
            is HoldWake.TryNow -> tryNow(wake.login)
        }
    }

    /** The console's try-now: the turn held longest on [login], or on every login when null, probes at once. Answers
     *  how many logins were probed. */
    public fun tryNow(login: String? = null): Int = synchronized(lock) {
        byLogin.filterKeys { login == null || it == login }.values
            .mapNotNull { holds -> holds.turns.firstOrNull()?.takeIf { it.signal(HoldWake.TryNow(login)) } }
            .size
    }

    /** A quota reading turned allowed: every login whose turns hold on a limit probes at once. */
    public fun allowed() {
        synchronized(lock) {
            byLogin.values.forEach { holds ->
                holds.turns.firstOrNull()?.takeIf { it.cause is FailureCause.Provider.Limited }
                    ?.signal(HoldWake.Allowed)
            }
        }
    }

    /** A login joined the head: every held turn moves to it, or to whichever login the pool chooses. */
    public fun joined() {
        synchronized(lock) { byLogin.values.flatMap { it.turns }.forEach { it.signal(HoldWake.Joined) } }
    }

    /** A request on [login] was answered 2xx: the turns sending on it are no longer held, and the ones still waiting go
     *  on, a second apart. */
    internal fun served(login: String) {
        synchronized(lock) {
            val holds = byLogin[login] ?: return
            holds.turns.removeAll { !it.waiting }
            holds.turns.forEach { it.signal(HoldWake.Served(login)) }
            if (holds.turns.isEmpty()) byLogin.remove(login)
        }
    }

    /** [turn] is held on its login: it joins the login's turns, keeping its place if it was held there before. */
    internal fun join(turn: HeldTurn) {
        synchronized(lock) {
            val holds = byLogin.getOrPut(turn.login) { LoginHolds() }
            // Arm before publication: a login can join or be served on another thread immediately after this lock.
            turn.arm()
            if (turn !in holds.turns) {
                turn.joinedAs = joined++
                holds.turns += turn
                holds.turns.sortWith(compareBy<HeldTurn>({ it.heldSinceMs }, { it.joinedAs }))
                holds.turns.filter { it !== turn }.forEach(HeldTurn::changed)
            }
        }
    }

    /** [turn] ended, or moved to another login: it leaves its login's turns. */
    internal fun leave(turn: HeldTurn) {
        synchronized(lock) {
            val holds = byLogin[turn.login] ?: return
            if (!holds.turns.remove(turn)) return
            holds.turns.forEach(HeldTurn::changed)
            if (holds.turns.isEmpty()) byLogin.remove(turn.login)
        }
    }

    /** How many turns are held on [turn]'s login ahead of it: 0 for the one held longest. */
    internal fun rank(turn: HeldTurn): Int = synchronized(lock) {
        byLogin[turn.login]?.turns?.indexOf(turn)?.coerceAtLeast(0) ?: 0
    }

    /** When [turn] probes next, on the wall clock, or null where it does not: only the turn held longest on a login
     *  probes it, nothing is probed for a hold that probes nothing, and a stated reset is waited for rather than
     *  probed at. */
    internal fun nextProbeMs(turn: HeldTurn, ask: HoldAsk): Long? = synchronized(lock) {
        val holds = byLogin[turn.login] ?: return null
        if (ask.probes == Probes.NONE || holds.turns.firstOrNull() !== turn) return null
        val at = (holds.lastProbeMs ?: ask.heldSinceMs) + ProbeSchedule.gapMs(ask.probes, holds.probes)
        val until = ask.until?.toEpochMilli()
        if (until != null && at >= until) null else at
    }

    /** [turn] probes its login now, at [nowMs] on the wall clock. */
    internal fun probed(turn: HeldTurn, nowMs: Long) {
        synchronized(lock) {
            val holds = byLogin[turn.login] ?: return
            holds.probes += 1
            holds.lastProbeMs = nowMs
        }
    }

    /** [turn] sends on its login at [nowMs] for its reset or a wake: the login's next probe counts from that send, so a
     *  send at one reset is not followed at once by a probe the first hold's start scheduled. */
    internal fun sent(turn: HeldTurn, nowMs: Long) {
        synchronized(lock) { byLogin[turn.login]?.lastProbeMs = nowMs }
    }

    /** A wake waiting for [turn]: completed by the next signal sent to it. */
    internal fun waitFor(turn: HeldTurn): Deferred<Unit> = synchronized(lock) { turn.wakeSignal() }

    /** The wake and the fresh wait are exchanged atomically with the publishers above. */
    internal fun takeSignal(turn: HeldTurn): HeldTurn.Signal = synchronized(lock) { turn.takeSignal() }
}
