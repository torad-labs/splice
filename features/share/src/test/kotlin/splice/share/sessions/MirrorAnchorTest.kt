// NEW: LAN share, round 12 of PR #419's review. The goal: one mirror that cannot be made never stops sessions crossing.
// Each mirror holds a pid by a process of its own, and a fork can be refused for want of memory or processes. While
// none can start, a session of the other machine is not mirrored and a note from one with no mirror here is refused,
// both said in the log; the link keeps looking and collecting, and once a process starts again the session is listed
// and its notes arrive. A machine whose mirrors cannot start ("f") joined torad over the real link, as
// TwoMachinesTest sets them up. Round 13 added a failure nothing foresaw ("g"): it ends neither the look nor the
// collection, each said in the log, and once it passes sessions and notes cross again.
package splice.share.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.sessions.mirror.MirrorAnchor
import splice.share.remote.RemoteSessions
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class MirrorAnchorTest : TwoMachinesTest() {

    @Test
    fun `while no mirror can start, nothing crosses and the log says why, and once one can, sessions and notes do`() {
        val refusing = AtomicBoolean(true)
        val strained = Machine(
            "f",
            MirrorAnchor {
                if (refusing.get()) throw IOException("error=35, Resource temporarily unavailable")
                // What the daemon's own anchor runs: a process that holds a pid until its pipe ends.
                ProcessBuilder("/bin/cat")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            },
        )
        val said = ConcurrentLinkedQueue<String>()
        try {
            val rig = torad.session("rig-lead")
            val splice = strained.session("splice")
            val joining = joined().second
            val links = links(strained, toradName, joining, port)
            checkNotNull(RemoteSessions(links, strained.mirrors, strained.acks, { said += it }, LOOK_MS).start(scope))
            listed(rig) { "$MINI/splice" in it }

            rig.send("$MINI/splice", "while none can start")
            runBlocking {
                val until = System.currentTimeMillis() + SETTLE_MS
                while (said.none { "was not delivered" in it } && System.currentTimeMillis() < until) delay(POLL_MS)
            }
            assertTrue(said.any { "was not delivered" in it }, "the refused note is said: $said")

            refusing.set(false)
            val mirrored = listed(splice) { "$toradName/rig-lead" in it }
            assertTrue("$toradName/rig-lead" in mirrored, "a later look mirrors it: $mirrored")
            rig.send("$MINI/splice", "once one can")
            val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note: $said" }
            assertEquals("once one can", got.text)
        } finally {
            strained.mirrors.close()
        }
    }

    @Test
    fun `a failure nothing foresaw ends neither the look nor the collection, and once it passes both carry on`() {
        val failing = AtomicBoolean(true)
        val broken = Machine(
            "g",
            MirrorAnchor {
                // Not a fork refused, which a mirror turns into a refusal: a failure nothing in the link foresaw.
                check(!failing.get()) { "the anchor broke" }
                ProcessBuilder("/bin/cat")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            },
        )
        val said = ConcurrentLinkedQueue<String>()
        try {
            val rig = torad.session("rig-lead")
            val splice = broken.session("splice")
            val joining = joined().second
            val links = links(broken, toradName, joining, port)
            checkNotNull(RemoteSessions(links, broken.mirrors, broken.acks, { said += it }, LOOK_MS).start(scope))
            listed(rig) { "$MINI/splice" in it }
            rig.send("$MINI/splice", "while it fails")
            runBlocking {
                val until = System.currentTimeMillis() + SETTLE_MS
                while (said.none { "was not delivered" in it } && System.currentTimeMillis() < until) delay(POLL_MS)
            }

            failing.set(false)

            val mirrored = listed(splice) { "$toradName/rig-lead" in it }
            assertTrue("$toradName/rig-lead" in mirrored, "the look carried on: $said")
            rig.send("$MINI/splice", "once it passes")
            val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "the collection carried on: $said" }
            assertEquals("once it passes", got.text)
        } finally {
            broken.mirrors.close()
        }
    }
}
