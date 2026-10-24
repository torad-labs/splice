// NEW: LAN share, round 12 of PR #419's review. The goal: imported commands behave like local commands, and editing
// which commands are shared does not cut a local turn. So a change to `[share]` that leaves its port, its on/off and
// its names as they were keeps the listener open: what a joined machine has in flight is answered, and its sessions
// stay listed here. And the daemon's stop closes sharing for good: no table asked for during its drain opens it again.
// torad shares over its real TLS listener and mini joined it, as TwoMachinesTest sets them up.
package splice.share.sessions

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.ShareConfig
import java.net.Socket

internal class ShareTableChangeTest : TwoMachinesTest() {

    /** Waits, up to a deadline, until torad has heard from mini since [before]. */
    private fun heardSince(before: String?) = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        while (heardFrom(MINI) == before && System.currentTimeMillis() < until) delay(POLL_MS)
        assertTrue(heardFrom(MINI) != before, "torad took mini's request in")
    }

    private fun answers(at: Int): Boolean = runCatching { Socket("127.0.0.1", at).close() }.isSuccess

    @Test
    fun `a change to the commands offered alone keeps mini's sessions here and answers what it has in flight`() {
        val rig = torad.session("rig-lead")
        mini.session("splice")
        val links = links(joined().second)
        offered(links)
        listed(rig) { "$MINI/splice" in it }
        val url = checkNotNull(links.url(toradName))
        val before = heardFrom(MINI)
        val held = scope.async { runCatching { links.api(toradName).outbox(url, null) } }
        heardSince(before)

        runBlocking { listener.follow(ShareConfig(enabled = true, port = port, heads = emptyList())) }

        assertTrue("$MINI/splice" in rig.agents(), "mini's session is still listed on torad: ${rig.agents()}")
        rig.send("$MINI/splice", "after the change")
        val answer = runBlocking { withTimeout(SETTLE_MS) { held.await() } }
        val carried = answer.getOrNull()?.notes?.map { it.text }
        assertEquals(listOf("after the change"), carried, "its collection carried it: ${answer.exceptionOrNull()}")
        assertEquals(emptyList<String>(), listener.state().share.heads, "the listener follows the new table")
    }

    @Test
    fun `a listener closed for the daemon's stop stays closed, whatever table is asked for after`() {
        assertTrue(answers(port), "torad shares on :$port")

        runBlocking { listener.stop() }
        listener.ask(ShareConfig(enabled = true, port = port))
        val after = runBlocking { listener.followAsked() }

        assertFalse(after.listening, "$after")
        assertFalse(answers(port), "nothing answers on :$port")
    }
}
