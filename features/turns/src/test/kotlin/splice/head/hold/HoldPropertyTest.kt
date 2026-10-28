// NEW: failure-handling slice 5 (2026-10-27). A login's held turns against a small reference model of section 9 ("What
// wakes it, whichever comes first"), over generated holds (firm, soft, unstated and credit), session walls, try-now
// actions, 2xx answers on the turns' own login and on another, quota readings turned allowed, logins joining and
// client closes. The model is this file's own reading of the design, written apart from HoldRegistry and HeldTurn:
// a held turn goes on at the first of its stated reset (2 s after, and 1 s later for each turn held longer on the
// login), a probe of its login (only the turn held longest probes: firm hourly, soft at 15 s, unstated at 1 minute,
// credit at 5 minutes, never at or past a stated reset), the console's try-now, another turn's 2xx on its login (a
// second apart, oldest first), a quota reading turned allowed (a limit's hold, not a credit hold), or a login joining.
// It ends at once when its client closes, at the session's wall and at seven days. Every expected value comes from
// that reading, never from a run.
package splice.head.hold

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.util.WallClock
import splice.head.hold.resources.SessionWall
import splice.upstream.Waiter
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldEnd
import splice.upstream.retry.Probes
import java.time.Instant
import kotlin.random.Random

private const val SCENARIOS = 600
private const val LOGIN = "login-a"
private const val OTHER_LOGIN = "login-b"
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY

/** The wall clock the scenarios start at: Oct 26, 2026, 12:00 UTC. */
private val BASE: Long = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()

/** The kinds of hold the design names, each with the probe its schedule opens with. */
private enum class Kind(val probes: Probes, val firstProbeMs: Long) {
    FIRM(Probes.FIRM, HOUR),
    SOFT(Probes.SOFT_LIFT, 15 * SECOND),
    UNSTATED(Probes.UNSTATED, MINUTE),
    CREDIT(Probes.CREDIT, 5 * MINUTE),
}

/** Something that happens while the turns hold, at [atMs] after they began. */
private sealed class Act {
    abstract val atMs: Long

    data class TryNow(override val atMs: Long, val login: String?) : Act()
    data class Served(override val atMs: Long, val login: String) : Act()
    data class Allowed(override val atMs: Long) : Act()
    data class Joined(override val atMs: Long) : Act()
    data class Close(override val atMs: Long, val turn: Int) : Act()
}

private data class Scenario(
    val kind: Kind,
    val turns: Int,
    /** The stated reset, after the start; null for a hold with none. */
    val untilMs: Long?,
    /** The session's wall less its margin, after the start; null for a session that outlasts every hold. */
    val wallMs: Long?,
    val acts: List<Act>,
)

/** How one turn's hold ended, and when, after the start. */
private data class Ended(val atMs: Long, val end: HoldEnd)

private object Scenarios {
    fun generate(random: Random): Scenario {
        val kind = Kind.entries.random(random)
        val untilMs = when (kind) {
            Kind.FIRM -> random.nextLong(1, 3 * DAY / SECOND) * SECOND
            Kind.SOFT -> random.nextLong(1, 6 * HOUR / SECOND) * SECOND
            Kind.UNSTATED, Kind.CREDIT -> null
        }
        // A wall ends on a quarter second and an act on 537 ms past one, so no two reasons fall in one millisecond.
        val wallMs = if (random.nextBoolean()) null else random.nextLong(0, 8 * DAY / SECOND) * SECOND + 250
        var at = 0L
        val acts = List(random.nextInt(0, 4)) {
            at += random.nextLong(5, 2 * DAY / SECOND) * SECOND
            act(random, at + 537)
        }
        return Scenario(kind, random.nextInt(1, 4), untilMs, wallMs, acts)
    }

    private fun act(random: Random, atMs: Long): Act = when (random.nextInt(6)) {
        0 -> Act.TryNow(atMs, if (random.nextBoolean()) null else LOGIN)
        1 -> Act.Served(atMs, LOGIN)
        2 -> Act.Served(atMs, OTHER_LOGIN)
        3 -> Act.Allowed(atMs)
        4 -> Act.Joined(atMs)
        else -> Act.Close(atMs, random.nextInt(3))
    }
}

/** The design's reading of the scenario: when and how each turn's hold ends. */
private object Model {
    fun ends(s: Scenario): List<Ended> {
        val ended = arrayOfNulls<Ended>(s.turns)
        val timers = List(s.turns) { timer(s, it) }
        s.acts.forEach { act ->
            // Every timer that fell before the act ended its turn first.
            timers.forEachIndexed { i, t -> if (ended[i] == null && t.atMs < act.atMs) ended[i] = t }
            val waiting = (0 until s.turns).filter { ended[it] == null }
            apply(s, act, waiting, ended)
        }
        return timers.mapIndexed { i, t -> ended[i] ?: t }
    }

    private fun apply(s: Scenario, act: Act, waiting: List<Int>, ended: Array<Ended?>) {
        when (act) {
            is Act.TryNow -> resumeOldest(act.atMs, waiting, ended)
            is Act.Allowed -> if (s.kind != Kind.CREDIT) resumeOldest(act.atMs, waiting, ended)
            is Act.Served -> served(act, waiting, ended)
            is Act.Joined -> waiting.forEach { ended[it] = Ended(act.atMs, HoldEnd.LOGIN_JOINED) }
            is Act.Close -> closed(act, waiting, ended)
        }
    }

    private fun resumeOldest(atMs: Long, waiting: List<Int>, ended: Array<Ended?>) {
        val oldest = waiting.firstOrNull()?.takeIf { it == 0 } ?: return
        ended[oldest] = Ended(atMs, HoldEnd.RESUME)
    }

    private fun served(act: Act.Served, waiting: List<Int>, ended: Array<Ended?>) {
        if (act.login == LOGIN) {
            waiting.forEachIndexed { j, i -> ended[i] = Ended(act.atMs + (j + 1) * SECOND, HoldEnd.RESUME) }
        }
    }

    private fun closed(act: Act.Close, waiting: List<Int>, ended: Array<Ended?>) {
        if (act.turn in waiting) ended[act.turn] = Ended(act.atMs, HoldEnd.CLIENT_GONE)
    }

    /** The first of the turn's own times: its reset, its login's probe, the session's wall and its seven days. */
    private fun timer(s: Scenario, turn: Int): Ended {
        val heldSince = turn.toLong()
        val candidates = listOfNotNull(
            s.untilMs?.let { Ended(it + 2 * SECOND + turn * SECOND, HoldEnd.RESUME) },
            probe(s, turn)?.let { Ended(it, HoldEnd.RESUME) },
            s.wallMs?.let { Ended(it, HoldEnd.WALL) },
            Ended(heldSince + WEEK, HoldEnd.MAX_HOLD),
        )
        return candidates.minBy { it.atMs }
    }

    private fun probe(s: Scenario, turn: Int): Long? {
        if (turn != 0) return null
        val at = s.kind.firstProbeMs
        return if (s.untilMs != null && at >= s.untilMs) null else at
    }
}

/** The scenario run through a registry and its held turns, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
private object Run {
    fun ends(s: Scenario): List<Ended> {
        lateinit var result: List<Ended>
        runTest(timeout = kotlin.time.Duration.INFINITE) { result = execute(s) }
        return result
    }

    private suspend fun TestScope.execute(s: Scenario): List<Ended> {
        val registry = HoldRegistry()
        val waiter = Waiter { ms -> delay(ms) }
        val wall = WallClock { BASE + testScheduler.currentTime }
        val lost = List(s.turns) { CompletableDeferred<Unit>() }
        val wallEnd = s.wallMs?.let { BASE + it } ?: Long.MAX_VALUE
        val turns = List(s.turns) { HeldTurn(registry, waiter, wall) }
        val held = turns.mapIndexed { i, turn ->
            async {
                val end = turn.hold(ask(s, i), wallEnd, lost[i])
                Ended(testScheduler.currentTime, end)
            }.also { testScheduler.runCurrent() }
        }
        val acts = launch {
            s.acts.forEach { act ->
                delay(act.atMs - testScheduler.currentTime)
                perform(registry, lost, act)
            }
        }
        val ends = held.awaitAll()
        acts.cancel()
        return ends
    }

    private fun perform(registry: HoldRegistry, lost: List<CompletableDeferred<Unit>>, act: Act) {
        when (act) {
            is Act.TryNow -> registry.tryNow(act.login)
            is Act.Served -> registry.served(act.login)
            is Act.Allowed -> registry.allowed()
            is Act.Joined -> registry.joined()
            is Act.Close -> if (act.turn < lost.size) lost[act.turn].complete(Unit)
        }
    }

    private fun ask(s: Scenario, turn: Int): HoldAsk {
        val until = s.untilMs?.let { Instant.ofEpochMilli(BASE + it) }
        val cause = when (s.kind) {
            Kind.FIRM -> FailureCause.Provider.Limited(
                Lift.At(until!!, true, LiftSource.RESET_HEADER),
                LimitWindow.FIVE_HOUR,
            )
            Kind.SOFT -> FailureCause.Provider.Limited(Lift.At(until!!, false, LiftSource.RETRY_AFTER), null)
            Kind.UNSTATED -> FailureCause.Provider.Limited(Lift.Unstated, LimitWindow.FIVE_HOUR)
            Kind.CREDIT -> FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false)
        }
        return HoldAsk(cause, Position(Moment.BEFORE_COMMIT, false), until, s.kind.probes, LOGIN, BASE + turn)
    }
}

class HoldPropertyTest {
    /** Nothing reaches the provider before the reset, a probe or a wake: each turn's hold ends exactly when, and as,
     *  the design's first reason says. That one comparison carries the other promises too: a firm hold is probed at
     *  most hourly apart from try-now, a client close ends its turn at once, and no hold outlives its seven days or
     *  its session's wall. */
    @Test
    fun `a login's held turns go on at the first of their reset, a probe or a wake, and end at a close or a bound`() {
        val random = Random(20261027)
        val misses = (1..SCENARIOS).mapNotNull {
            val s = Scenarios.generate(random)
            val expected = Model.ends(s)
            val actual = Run.ends(s)
            if (actual == expected) null else "$s\n  expected $expected\n  actual   $actual"
        }
        assertEquals(emptyList<String>(), misses.take(5), "${misses.size} of $SCENARIOS scenarios differ")
    }

    /** A held compaction never outlives its timer: whatever the conversation's size and the session's client timeout,
     *  the hold's wall falls 30 s before the sooner of the two. */
    @Test
    fun `a held compaction's wall falls before its timer and its client timeout`() {
        val random = Random(1027)
        repeat(SCENARIOS) {
            val bodyBytes = random.nextLong(1_000L, 40_000_000L)
            val clientWall = random.nextLong(60_000L, WEEK + HOUR)
            // Claude Code's compaction timer: 1.5 ms a token, about four bytes a token, from 10 minutes to an hour.
            val timer = (bodyBytes / 4 * 3 / 2).coerceIn(10 * MINUTE, HOUR)
            val endsAt = minOf(clientWall, ClientWallHeader.compactionTimerMs(bodyBytes))
            val wall = SessionWall({ 0L }, endsAt)
            assertTrue(wall.capAtMs() <= minOf(timer, clientWall) - 30 * SECOND, "body $bodyBytes, wall $clientWall")
            assertEquals(minOf(timer, clientWall), wall.leftMs(), "body $bodyBytes, wall $clientWall")
        }
    }
}
