// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The goal: a Claude Code session on one machine sees
// the live sessions of a machine it is joined with in ListAgents, as `<machine>/<name>`, and messages them with
// SendMessage, each way; a session that ends leaves the other machine at the next look; and only a joined machine is
// shown any. Since the round-1 review (the President's decision, Oct 23), sessions cross only while the Sessions switch
// is on, so these cases turn both machines' switches on, as Marcos does on each Sharing page; the switch's own cases
// are SessionsSwitchAcrossMachinesTest's. The machines are TwoMachinesTest's.
package splice.share.sessions

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.sessions.mirror.MirrorCounts
import splice.sessions.peer.DeliveryStatus
import splice.sessions.registry.SessionAvailability
import splice.share.ShareState
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteNames
import splice.share.remote.RemoteSessions
import splice.share.remote.RemoteTrust
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

// why: a session's name as Claude Code's ListAgents can show it, its SendMessage takes it, and an envelope's
// from-name can carry it. Never `<name>@<machine>`: SendMessage refuses an @ as a teammate's team ("to must be a bare
// teammate name", Claude Code 2.1.309, Oct 23).
private val SAFE_MIRROR = Regex("^$MINI/[A-Za-z0-9._-]{1,64}$")

internal class SessionsAcrossMachinesTest : TwoMachinesTest() {

    @Test
    fun `each machine lists the other's live sessions under that machine's name, and never its own back`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        val onMini = listed(splice) { "$toradName/rig-lead" in it }
        val onTorad = listed(rig) { "$MINI/splice" in it }
        assertEquals(setOf("$toradName/rig-lead"), onMini, "mini shows torad's session, and no mirror of its own")
        assertEquals(setOf("$MINI/splice"), onTorad, "torad shows mini's session, and no mirror of its own")
    }

    /** Waits, up to a deadline, until [machine] reads [name] at [now] as [wanted], and answers how it reads it then. */
    private fun reads(machine: Machine, name: String, now: Long, wanted: SessionAvailability) = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var read = machine.availability(name, now)
        while (read != wanted && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            read = machine.availability(name, now)
        }
        read
    }

    @Test
    fun `two machines that each joined the other list each other's sessions once, whatever case their names are in`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val miniPort = TestPorts.reserve()
        val miniSharing = sharing(mini, miniPort)
        opened += AutoCloseable { runBlocking { miniSharing.stop() } }
        // mini joins torad under its host name, and torad joins mini back under its own, each as `splice remote` does,
        // keying the other by the name its listener gives.
        val (toradInfo, joined) = joined(MIXED)
        val miniLinks = links(joined)
        val miniName = RemoteNames.of(MIXED)
        val back = RemoteJoin(toradInfo.server).join(RemoteFound("127.0.0.1", miniPort, described(miniPort)))
        val toradLinks = links(torad, miniName, back as RemoteJoined.Joined, miniPort)
        assertTrue(miniSharing.clients.allowSessions(RemoteNames.of(toradInfo.server), true), "torad joined mini")

        // Each machine's sessions reach the other both ways: offered by their own machine, and looked at by the other.
        offered(miniLinks)
        link(toradLinks, torad)
        listed(splice) { it.isNotEmpty() }
        val onTorad = listed(rig) { "$miniName/splice" in it }
        link(miniLinks)
        listed(splice) { "$toradName/rig-lead" in it }

        splice.send("$toradName/rig-lead", "hello from mini")
        val got = checkNotNull(rig.heard(SETTLE_MS / 1_000)) { "torad's session took the message" }
        assertEquals("$miniName/splice", got.fromName, "the sender is named as torad names mini")
        rig.send(checkNotNull(got.from), "hello back")
        val reply = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "the reply reached mini's session" }
        assertEquals("$toradName/rig-lead", reply.fromName, "the replier is named as mini names torad")

        assertEquals(setOf("$miniName/splice"), onTorad, "torad lists mini's session once")
        assertEquals(setOf("$miniName/splice"), rig.agents(), "and still once after both links ran")
        assertEquals(setOf("$toradName/rig-lead"), splice.agents(), "mini lists torad's session once")
    }

    @Test
    fun `a mirror reads stale or live as its session on the other machine does`() {
        val now = System.currentTimeMillis()
        val rig = torad.session("rig-lead", heardAt = now - HOUR_MS)
        mini.session("splice")
        join()
        val mirror = "$toradName/rig-lead"
        val idle = reads(mini, mirror, now, SessionAvailability.STALE)
        assertEquals(SessionAvailability.STALE, idle, "idle an hour there")

        rig.busyAt(now)
        val busy = reads(mini, mirror, now, SessionAvailability.LIVE)
        assertEquals(SessionAvailability.LIVE, busy, "busy there now")
    }

    @Test
    fun `a message crosses to the session named, and its reply comes back the same way`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }

        splice.send("$toradName/rig-lead", "hello from mini")
        val got = checkNotNull(rig.heard(SETTLE_MS / 1_000)) { "torad's session took the message" }
        assertEquals("hello from mini", got.text)
        assertEquals("$MINI/splice", got.fromName, "the sender is named as the other machine's session")

        rig.send(checkNotNull(got.from), "hello back")
        val back = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "the reply reached mini's session" }
        assertEquals("hello back", back.text)
        assertEquals("$toradName/rig-lead", back.fromName)
    }

    @Test
    fun `a note written while the joined machine's collection is cut reaches it at its next collection`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val (info, joined) = joined()
        val links = links(joined)
        offered(links)
        listed(rig) { "$MINI/splice" in it }

        cutCollection(info, joined)
        rig.send("$MINI/splice", "while mini was away")
        link(links)
        val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        assertEquals("while mini was away", got.text)
        assertEquals("$toradName/rig-lead", got.fromName)
    }

    @Test
    fun `a note reaches its session here though the other machine's mirrors were withdrawn since the last look`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        // A link whose next look is past the test's wait: until then, what reaches mini comes through its collection.
        checkNotNull(RemoteSessions(links(joined().second), mini.mirrors, { }, SLOW_LOOK_MS).start(scope))
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }

        // torad's mirrors here go between two looks, as a failed look, the switch turned off and on again, or the end
        // of torad's own offers where it joined mini too each withdraws them.
        mini.mirrors.withdraw(toradName)
        assertTrue("$toradName/rig-lead" !in listed(splice) { "$toradName/rig-lead" !in it }, "withdrawn")

        rig.send("$MINI/splice", "after the withdraw")
        val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        assertEquals("after the withdraw", got.text)
        assertEquals("$toradName/rig-lead", got.fromName, "from torad's session, as mini names it")
    }

    @Test
    fun `a note waiting for the joined machine outlasts a change to what this machine shares`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val links = links(joined().second)
        offered(links)
        listed(rig) { "$MINI/splice" in it }

        rig.send("$MINI/splice", "before the change")
        // A collection mini makes and never acknowledges, as one whose deliveries a restart cut short: it returns as
        // soon as the note waits, and leaves it there.
        val waiting = links.api(toradName).outbox(checkNotNull(links.url(toradName)), null)
        assertEquals(listOf("before the change"), waiting.notes.map { it.text }, "the note waits for mini")
        runBlocking { listener.follow(ShareConfig(enabled = true, port = port, hostnames = listOf("torad.lan"))) }
        link(links)
        val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        assertEquals("before the change", got.text)
    }

    @Test
    fun `a session that ends leaves the other machine, and one that starts joins it`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }

        rig.close()
        val after = listed(splice) { "$toradName/rig-lead" !in it }
        assertTrue("$toradName/rig-lead" !in after, "an ended session is no longer listed on the other machine")

        torad.session("reviewer")
        assertTrue("$toradName/reviewer" in listed(splice) { "$toradName/reviewer" in it }, "a new session appears")
    }

    /** Waits, up to a deadline, until [machine]'s counts for [other] are [wanted], and answers them then. */
    private fun counted(machine: Machine, other: String, wanted: MirrorCounts) = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var counts = machine.mirrors.counts(other)
        while (counts != wanted && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            counts = machine.mirrors.counts(other)
        }
        counts
    }

    @Test
    fun `each machine counts the sessions crossing with the other each way, until it stops looking`() {
        torad.session("rig-lead")
        mini.session("splice")
        mini.session("review")
        val link = join()

        assertEquals(MirrorCounts(here = 1, there = 2), counted(mini, toradName, MirrorCounts(1, 2)), "on mini")
        assertEquals(MirrorCounts(here = 2, there = 1), counted(torad, MINI, MirrorCounts(2, 1)), "on torad")

        runBlocking { link.cancelAndJoin() }
        val quiet = MirrorCounts(here = 0, there = null)
        assertEquals(quiet, counted(torad, MINI, quiet), "a machine that stopped looking is counted nothing either way")
    }

    @Test
    fun `a joined machine that stops looking leaves the sharing machine's listing`() {
        val rig = torad.session("rig-lead")
        mini.session("splice")
        val link = join()
        listed(rig) { "$MINI/splice" in it }

        runBlocking { link.cancelAndJoin() }
        val after = listed(rig) { "$MINI/splice" !in it }
        assertTrue("$MINI/splice" !in after, "a machine that stopped looking has no session listed on the other")
    }

    @Test
    fun `a machine that stops sharing and the machine that joined it each leave the other's listing`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }

        runBlocking { listener.stop() }
        val onMini = listed(splice) { "$toradName/rig-lead" !in it }
        assertTrue("$toradName/rig-lead" !in onMini, "the joined machine no longer lists one that stopped sharing")
        val onTorad = listed(rig) { "$MINI/splice" !in it }
        assertTrue("$MINI/splice" !in onTorad, "the machine that stopped sharing no longer lists the joined one's")
    }

    @Test
    fun `whatever another machine names its sessions, each is listed here under a safe name of its own`() {
        val rig = torad.session("rig-lead")
        val random = Random(NAMES_SEED)
        val names = mutableListOf<String>()
        repeat(NAMES_DRAWN) { at ->
            // Every fifth session takes a name already drawn: two sessions there may share one.
            names += if (at % 5 == 4) names[random.nextInt(names.size)] else drawnName(random)
        }
        val sessions = names.map { UUID(random.nextLong(), random.nextLong()).toString() to it }
        assertEquals(200, offer(sessions))

        val listed = rig.agents().filter { it.startsWith("$MINI/") }
        assertEquals(NAMES_DRAWN, listed.size, "every session offered is listed")
        listed.forEach { assertTrue(SAFE_MIRROR.matches(it), "'$it' is a name ListAgents shows and a reply uses") }
        assertEquals(listed.size, listed.toSet().size, "no two are listed under one name")
    }

    @Test
    fun `a machine is mirrored no more sessions than the most one daemon hosts, however many it offers`() {
        val rig = torad.session("rig-lead")
        val sessions = (1..MIRRORS_AT_MOST + 44).map { UUID(0L, it.toLong()).toString() to "s$it" }
        assertEquals(200, offer(sessions))
        assertEquals(MIRRORS_AT_MOST, rig.agents().count { it.startsWith("$MINI/") })
    }

    // The round-3 review: a note from a session this machine cannot mirror is refused, and its sender is told why.
    // Found on the way: at that many mirrors the daemon stopped answering at all, so it must still answer, at once.
    @Test
    fun `a note from a session past the most mirrors one daemon hosts is refused for that, in words`() {
        torad.session("rig-lead")
        val rigId = torad.mirrors.local().single { it.name == "rig-lead" }.id
        val sessions = (1..MIRRORS_AT_MOST).map { UUID(0L, it.toLong()).toString() to "s$it" }
        val (info, joined) = joined()
        assertEquals(200, offer(sessions, info to joined))
        val other = RemoteJoin("other").join(RemoteFound("127.0.0.1", port, info))
        assertTrue(other is RemoteJoined.Joined, "the listener still takes a join: $other")
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val note = """{"from":{"id":"s-late","name":"late"},"text":"one too many"}"""
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions/$rigId/message"))
            .header("Authorization", "Bearer ${joined.token}")
            .POST(HttpRequest.BodyPublishers.ofString(note))
            .timeout(Duration.ofMillis(SETTLE_MS))
            .build()
        val answer = Json.parseToJsonElement(http.send(asked, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        assertEquals(DeliveryStatus.REFUSED.wire, answer.getValue("status").jsonPrimitive.content, answer.toString())
        val why = answer.getValue("detail").jsonPrimitive.content
        assertTrue("$MIRRORS_AT_MOST" in why, "the sender is told this machine hosts no more mirrors: $why")
        assertTrue("not offered" !in why, "and not that mini never offered its sessions: $why")
    }

    // The round-3 review: phase 1 booked a machine under the name it gave, capitals and all. Its re-join books it under
    // its `[remotes]` spelling, which takes the old entry's place, so the Sharing page shows the machine once.
    @Test
    fun `a machine booked under the name it gave in phase 1 is booked once when it joins again`() {
        val book = ShareState(torad.paths).clientsFile
        Files.createDirectories(book.parent)
        Files.writeString(
            book,
            """{"clients":[{"name":"$MIXED","token_sha256":"00","address":"10.0.0.2",""" +
                """"joined_at":"2026-10-22T12:00:00Z"}]}""",
        )
        joined(MIXED)
        assertEquals(listOf(RemoteNames.of(MIXED)), listener.clients.all().map { it.name })
    }

    @Test
    fun `a machine that has not joined is shown no sessions`() {
        torad.session("rig-lead")
        val info = checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", port).get())
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions"))
            .header("Authorization", "Bearer not-a-joined-token").GET().build()
        val answer = http.send(asked, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, answer.statusCode(), answer.body())
        assertTrue("rig-lead" !in answer.body())
    }
}
