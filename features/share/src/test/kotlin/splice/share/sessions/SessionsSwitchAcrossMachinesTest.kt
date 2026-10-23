// NEW: LAN share, phase 2, the round-1 and round-2 reviews (docs/specs/lan-share.md, Bounds). The goal, the
// President's decision of Oct 23: no joined machine can list, write into or read from this machine's sessions until
// Marcos turns that machine's Sessions switch on, in either direction of the pair; and a machine that joined this one
// and that this one joined is one machine with one switch, so an Off on either of its cards stops both links. The
// machines are TwoMachinesTest's.
package splice.share.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.share.ShareInfo
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteSessions
import splice.share.remote.RemoteTrust
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

internal class SessionsSwitchAcrossMachinesTest : TwoMachinesTest() {

    /** What torad answers the machine holding [token] on each session route, in turn: its listing, an offer of mini's
     *  session, a collection, and a note from that session to the session here whose id is [to]. */
    private fun answers(info: ShareInfo, token: String, to: String): List<HttpResponse<String>> {
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val sessions = "https://127.0.0.1:$port/share/v1/sessions"
        val offer = """{"sessions":[{"id":"s-mini","name":"splice"}]}"""
        val note = """{"from":{"id":"s-mini","name":"splice"},"text":"hello from mini"}"""
        return listOf(
            HttpRequest.newBuilder(URI.create(sessions)).GET(),
            HttpRequest.newBuilder(URI.create(sessions)).PUT(HttpRequest.BodyPublishers.ofString(offer)),
            HttpRequest.newBuilder(URI.create("$sessions/outbox?wait=0")).GET(),
            HttpRequest.newBuilder(URI.create("$sessions/$to/message")).POST(HttpRequest.BodyPublishers.ofString(note)),
        ).map { asked ->
            http.send(asked.header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    @Test
    fun `a joined machine is refused every session route until its Sessions switch is on here, and once it is off`() {
        val rig = torad.session("rig-lead")
        val rigId = torad.mirrors.local().single { it.name == "rig-lead" }.id
        val (info, joined) = joined(sessions = false)
        val refused = answers(info, joined.token, rigId)
        assertEquals(List(4) { 403 }, refused.map { it.statusCode() }, "a machine that only joined reaches no session")
        refused.forEach { answer ->
            assertTrue("rig-lead" !in answer.body(), answer.body())
            assertTrue("Sessions" in answer.body(), "the refusal names the switch: ${answer.body()}")
        }
        assertTrue(rig.agents().none { it.startsWith("$MINI/") }, "its offer made no mirror here")
        assertEquals(null, rig.heard(0), "its note reached no session")

        assertTrue(listener.clients.allowSessions(MINI, true))
        val allowed = answers(info, joined.token, rigId)
        assertEquals(List(4) { 200 }, allowed.map { it.statusCode() }, allowed.map { it.body() }.toString())
        assertTrue("rig-lead" in allowed.first().body(), "it lists this machine's sessions")
        assertEquals("hello from mini", rig.heard(SETTLE_MS / 1_000)?.text, "its note reaches the session")

        assertTrue(listener.clients.allowSessions(MINI, false))
        val off = answers(info, joined.token, rigId).map { it.statusCode() }
        assertEquals(List(4) { 403 }, off, "a switch turned off refuses it again")
    }

    @Test
    fun `a machine that joins again starts with its Sessions switch off`() {
        torad.session("rig-lead")
        val rigId = torad.mirrors.local().single { it.name == "rig-lead" }.id
        joined()
        val (info, again) = joined(sessions = false)
        val answered = answers(info, again.token, rigId).map { it.statusCode() }
        assertEquals(List(4) { 403 }, answered, "a new join is a machine the switch has not been turned on for")
    }

    @Test
    fun `a machine whose Sessions switch is off for the machine it joined crosses no session with it until it is on`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val links = links(joined().second, sessions = false)
        val looks = AtomicInteger()
        val counted = Ticker { ms -> ProcessTicker().awaitTick(ms).also { looks.incrementAndGet() } }
        checkNotNull(RemoteSessions(links, mini.mirrors, { }, LOOK_MS, counted).start(scope))
        val looked = runBlocking {
            val until = System.currentTimeMillis() + SETTLE_MS
            while (looks.get() < LOOKS_OFF && System.currentTimeMillis() < until) delay(POLL_MS)
            looks.get() >= LOOKS_OFF
        }
        assertTrue(looked, "mini's link went round $LOOKS_OFF times")
        assertEquals(null, heardFrom(MINI), "mini asked torad nothing of its sessions")
        assertEquals(emptySet<String>(), splice.agents(), "mini lists none of torad's sessions")
        assertTrue(rig.agents().none { it.startsWith("$MINI/") }, "torad lists none of mini's")

        assertTrue(store(mini).allowSessions(toradName, true))
        assertTrue("$toradName/rig-lead" in listed(splice) { "$toradName/rig-lead" in it }, "on, mini lists torad's")
        assertTrue("$MINI/splice" in listed(rig) { "$MINI/splice" in it }, "and torad lists mini's, with no restart")

        assertTrue(store(mini).allowSessions(toradName, false))
        assertTrue("$toradName/rig-lead" !in listed(splice) { "$toradName/rig-lead" !in it }, "off, mini lists none")
        assertTrue("$MINI/splice" !in listed(rig) { "$MINI/splice" !in it }, "and torad none of mini's")
    }

    @Test
    fun `a joined machine whose Sessions switch is turned off here leaves our listing, and ours leave its`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }

        assertTrue(listener.clients.allowSessions(MINI, false))
        assertTrue("$MINI/splice" !in listed(rig) { "$MINI/splice" !in it }, "torad no longer lists mini's session")
        assertTrue("$toradName/rig-lead" !in listed(splice) { "$toradName/rig-lead" !in it }, "nor mini torad's")
    }

    /** Waits until [splice] on mini lists none of torad's sessions and [rig] on torad none of mini's, then [looks]
     *  more of the links' looks, and checks neither came back. */
    private fun apart(splice: ClaudeCodeSession, rig: ClaudeCodeSession, looks: AtomicInteger) {
        listed(splice) { it.none { name -> name.startsWith("$toradName/") } }
        listed(rig) { it.none { name -> name.startsWith("$MINI/") } }
        val from = looks.get()
        val looked = runBlocking {
            val until = System.currentTimeMillis() + SETTLE_MS
            while (looks.get() < from + LOOKS_OFF * 2 && System.currentTimeMillis() < until) delay(POLL_MS)
            looks.get() >= from + LOOKS_OFF * 2
        }
        assertTrue(looked, "both links went round $LOOKS_OFF times each")
        assertEquals(emptySet<String>(), splice.agents(), "mini lists none of torad's sessions")
        assertEquals(emptySet<String>(), rig.agents(), "torad lists none of mini's")
    }

    @Test
    fun `two machines that each joined the other cross no session while either switch for the other is off`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val miniPort = TestPorts.reserve()
        val miniSharing = sharing(mini, miniPort)
        opened += AutoCloseable { runBlocking { miniSharing.stop() } }
        val (toradInfo, joined) = joined()
        val back = RemoteJoin(toradInfo.server).join(RemoteFound("127.0.0.1", miniPort, described(miniPort)))
        val toradLinks = links(torad, MINI, back as RemoteJoined.Joined, miniPort)
        assertTrue(miniSharing.clients.allowSessions(toradName, true), "torad joined mini")
        val looks = AtomicInteger()
        val counted = Ticker { ms -> ProcessTicker().awaitTick(ms).also { looks.incrementAndGet() } }
        checkNotNull(RemoteSessions(links(joined), mini.mirrors, { }, LOOK_MS, counted).start(scope))
        checkNotNull(RemoteSessions(toradLinks, torad.mirrors, { }, LOOK_MS, counted).start(scope))
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }

        // mini's card for torad as a machine it joined goes off: torad's own link to mini, which mini's other card
        // lets in, carries nothing either.
        assertTrue(store(mini).allowSessions(toradName, false))
        apart(splice, rig, looks)
        assertTrue(store(mini).allowSessions(toradName, true))
        assertTrue("$toradName/rig-lead" in listed(splice) { "$toradName/rig-lead" in it }, "on again, mini lists it")
        assertTrue("$MINI/splice" in listed(rig) { "$MINI/splice" in it }, "and torad lists mini's")

        // Then its card for torad as a machine that joined it, as a re-join turns it off: mini's own link carries
        // nothing either.
        assertTrue(miniSharing.clients.allowSessions(toradName, false))
        apart(splice, rig, looks)
    }
}
