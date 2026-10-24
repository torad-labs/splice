// NEW: LAN share, phase 2, the round-3 review (docs/specs/lan-share.md, Bounds). The goal: another machine's session is
// listed here only while that machine is heard from, whatever made its mirror (a look, an offer, or a note that came
// from it), and never once sessions stop crossing with it, by a switch turned off or by this machine no longer sharing.
// Round 14 of PR #419's review added the daemon's stop, after which a request still on its way makes no mirror, and an
// offer that fails part way, whose mirrors made before the failure still end with their time. Round 18 added the
// moment between a way's time running out and the sweep that withdraws it: a note that lands then gives its sender's
// way the note's time from its arrival, as one landing after the sweep would. The machines are TwoMachinesTest's.
package splice.share.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.util.WallClock
import splice.sessions.mirror.MirrorAnchor
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirrorPlace
import splice.sessions.mirror.MirrorSweep
import splice.sessions.mirror.MirrorWay
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.share.ShareInfo
import splice.share.remote.RemoteSessions
import splice.share.remote.RemoteTrust
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// why: a fixed seed, so a failing run of looks, notes and withdraws is the same run next time.
private const val LIFETIME_SEED = 31

// why: enough runs that a note lands on a machine with no time left, one with time, and one just withdrawn.
private const val LIFETIME_RUNS = 12

// why: steps per run: several looks, notes, withdraws and clock moves for each machine.
private const val LIFETIME_STEPS = 30

// why: the longest time one look, offer or note gives a machine's mirrors in these runs, and the most one step moves
// the clock, so a machine's time runs out in some steps and not in others.
private const val LIFETIME_SPAN_MS = 3_000L

// why: the mirror of an offer whose anchor fails in a way nothing foresaw: two are made before it.
private const val BROKEN_AT = 3

// why: two machines, so one machine's withdraw or end is seen to leave the other's mirrors alone.
private val LIFETIME_MACHINES = listOf("far", "near")

// why: a few sessions per machine, so looks drop and add some and notes come from mirrored and unmirrored ones.
private val LIFETIME_IDS = listOf("s1", "s2", "s3", "s4")

internal class MirrorLifetimeTest : TwoMachinesTest() {

    /** A note to the session on torad whose id is [to], from mini's session [from] (id to name), as mini's link sends
     *  one, and torad's answer. */
    private fun note(info: ShareInfo, token: String, to: String, from: Pair<String, String>): HttpResponse<String> {
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val body = """{"from":{"id":"${from.first}","name":"${from.second}"},"text":"hello from mini"}"""
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions/$to/message"))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(asked, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a session another machine wrote from is listed here only until this machine stops sharing, offered or not`() {
        val rig = torad.session("rig-lead")
        val rigId = torad.mirrors.local().single { it.name == "rig-lead" }.id
        val (info, joined) = joined()
        val answer = note(info, joined.token, rigId, "s-ghost" to "ghost")
        assertEquals(200, answer.statusCode(), answer.body())
        assertTrue("$MINI/ghost" in listed(rig) { "$MINI/ghost" in it }, "its sender is a mirror here, for the reply")

        runBlocking { listener.stop() }
        assertTrue("$MINI/ghost" !in listed(rig) { "$MINI/ghost" !in it }, "and leaves once this machine stops sharing")
    }

    // The class the round-3 review named: several writers each kept their own rule for when a mirror ends. The
    // property: whatever made a machine's mirrors (a look, an offer, or a note from one of its sessions), they are
    // withdrawn once the time its last look, offer or note gave them is past, and not before, however late the sweep
    // that withdraws them runs; and a withdraw ends only that machine's. A model of each machine's sessions and end,
    // over generated runs.
    @Test
    fun `no mirror outlasts the time its machine's last look, offer or note gave it, and none ends before`() {
        torad.session("rig-lead")
        val to = torad.mirrors.local().single().id
        val now = AtomicLong()
        val random = Random(LIFETIME_SEED)
        repeat(LIFETIME_RUNS) { run ->
            val place = MirrorPlace(torad.registry, torad.sockets, torad.home.resolve("lifetime.log"))
            // The run moves the clock and looks for ended mirrors itself, so the mirrors never look on their own.
            val sweep = MirrorSweep { false }
            val mirrors = SessionMirrors(place, scope, Dispatchers.IO, { }, sweep, WallClock { now.get() })
            val model = LifetimeRun(mirrors, now, to, random)
            try {
                repeat(LIFETIME_STEPS) { step ->
                    model.step()
                    LIFETIME_MACHINES.forEach { machine ->
                        val counted = mirrors.counts(machine).here
                        assertEquals(model.expected(machine), counted, "run $run, step $step: $machine's mirrors")
                    }
                }
                now.addAndGet(LIFETIME_SPAN_MS)
                mirrors.expire()
                LIFETIME_MACHINES.forEach { machine ->
                    assertEquals(0, mirrors.counts(machine).here, "run $run: $machine outlived its time")
                }
            } finally {
                mirrors.close()
            }
        }
    }

    @Test
    fun `an offer that arrives once the daemon withdrew every mirror makes none`() {
        val rig = torad.session("rig-lead")
        val carrier = MirrorCarrier { _, _, _ -> MirrorCarried(null) }

        torad.mirrors.close()
        val late = listOf(MirroredSession("s-late", "late"))
        torad.mirrors.mirror(MINI, late, MirrorLink(carrier, HOUR_MS, MirrorWay.OFFER))

        assertTrue("$MINI/late" !in rig.agents(), "no record of a mirror outlives the daemon's stop")
    }

    @Test
    fun `mirrors an offer made before it failed part way still end with their time`() {
        val now = AtomicLong(System.currentTimeMillis())
        val opened = AtomicInteger()
        val place = MirrorPlace(torad.registry, torad.sockets, torad.home.resolve("part.log"))
        val anchor = MirrorAnchor {
            // A failure nothing foresaw, on the third mirror of the offer.
            check(opened.incrementAndGet() != BROKEN_AT) { "the anchor broke" }
            ProcessBuilder("/bin/cat")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        val clock = WallClock { now.get() }
        val mirrors = SessionMirrors(place, scope, Dispatchers.IO, { }, MirrorSweep { false }, clock, anchor)
        val carrier = MirrorCarrier { _, _, _ -> MirrorCarried(null) }
        val offered = (1..BROKEN_AT + 1).map { MirroredSession("s$it", "far-$it") }
        try {
            assertThrows<IllegalStateException> {
                mirrors.mirror("far", offered, MirrorLink(carrier, LOOK_MS, MirrorWay.OFFER))
            }
            assertEquals(BROKEN_AT - 1, mirrors.counts("far").here, "the mirrors made before it failed")

            now.addAndGet(HOUR_MS)
            mirrors.expire()
            assertEquals(0, mirrors.counts("far").here, "end with the time the offer gave them")
        } finally {
            mirrors.close()
        }
    }

    @Test
    fun `a mirror of a machine whose Sessions switch is off here goes at the next look, whatever made it`() {
        val splice = mini.session("splice")
        val spliceId = mini.mirrors.local().single { it.name == "splice" }.id
        val links = links(joined().second, sessions = false)
        val looks = AtomicInteger()
        val counted = Ticker { ms -> ProcessTicker().awaitTick(ms).also { looks.incrementAndGet() } }
        checkNotNull(RemoteSessions(links, mini.mirrors, mini.acks, { }, LOOK_MS, counted).start(scope))
        val looked = runBlocking {
            val until = System.currentTimeMillis() + SETTLE_MS
            while (looks.get() < LOOKS_OFF && System.currentTimeMillis() < until) delay(POLL_MS)
            looks.get() >= LOOKS_OFF
        }
        assertTrue(looked, "mini's link went round $LOOKS_OFF times with the switch off")

        // A collection that passed the switch before it went off delivers after the link withdrew torad's mirrors.
        val late = MirrorCarrier { _, _, _ -> MirrorCarried(null) }
        val from = MirroredSession("s-late", "late")
        val link = MirrorLink(late, HOUR_MS, MirrorWay.LOOK)
        runBlocking { mini.mirrors.deliver(spliceId, toradName, from, "late", link) }
        assertTrue("$toradName/late" in splice.agents(), "its sender was made a mirror here")
        assertTrue("$toradName/late" !in listed(splice) { "$toradName/late" !in it }, "and goes at the next look")
    }
}

/** One generated run against [mirrors], whose clock is [now]: each step a look or an offer, a note to the session here
 *  whose id is [to], a withdraw, the clock moving on, or the sweep that checks the mirrors' time; and each machine's
 *  mirrors as they should then be, the sessions mirrored and when they end. */
private class LifetimeRun(
    private val mirrors: SessionMirrors,
    private val now: AtomicLong,
    private val to: String,
    private val random: Random,
) {
    private val carrier = MirrorCarrier { _, _, _ -> MirrorCarried(null) }
    private val ends = mutableMapOf<String, Long>()
    private val held = mutableMapOf<String, Set<String>>()

    /** One step, at random, on one machine at random. */
    fun step() {
        val machine = LIFETIME_MACHINES.random(random)
        val link = MirrorLink(carrier, random.nextLong(1, LIFETIME_SPAN_MS), MirrorWay.LOOK)
        when (random.nextInt(5)) {
            0 -> looked(machine, link)
            1 -> noted(machine, link)
            2 -> {
                mirrors.withdraw(machine, MirrorWay.LOOK)
                forget(machine)
            }
            // The clock moves on, and the sweep, which runs each second, has not run since.
            3 -> now.addAndGet(random.nextLong(0, LIFETIME_SPAN_MS))
            else -> {
                mirrors.expire()
                ends.filterValues { it < now.get() }.keys.forEach(::forget)
            }
        }
    }

    /** How many of [machine]'s sessions should be mirrored now. */
    fun expected(machine: String): Int = held[machine].orEmpty().size

    /** A look or an offer: these are [machine]'s sessions now, and they last [link]'s time more. */
    private fun looked(machine: String, link: MirrorLink) {
        val ids = LIFETIME_IDS.filter { random.nextBoolean() }.toSet()
        mirrors.mirror(machine, ids.map { MirroredSession(it, "n-$it") }, link)
        ends[machine] = maxOf(ends[machine] ?: 0L, now.get() + link.lastsMs)
        held[machine] = ids
    }

    /** A note from one of [machine]'s sessions: its sender is mirrored, and [machine] lasts at least [link]'s time from
     *  now, whether its time had run out before the sweep or not. */
    private fun noted(machine: String, link: MirrorLink) {
        val id = LIFETIME_IDS.random(random)
        runBlocking { mirrors.deliver(to, machine, MirroredSession(id, "n-$id"), "hello", link) }
        ends[machine] = maxOf(ends[machine] ?: 0L, now.get() + link.lastsMs)
        held[machine] = held[machine].orEmpty() + id
    }

    private fun forget(machine: String) {
        ends.remove(machine)
        held.remove(machine)
    }
}
