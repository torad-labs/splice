// NEW: LAN share, round 14 of PR #419's review. The goal: a daemon that stops while it hands over notes from a machine
// it joined hands none of them over twice once it starts again (docs/specs/lan-share.md, Messages). A collection brings
// a batch of notes at once; the stop lands while they are being handed to the session here, as `splice restart --now`
// does, and the link that starts after it acknowledges each note handed over before the stop. The machines are
// TwoMachinesTest's.
package splice.share.sessions

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// why: more notes than are handed over in the moment a stop takes to land, under the 64 one collection brings.
private const val BATCH = 40

// why: the note sent once the link started again, so the test knows the link after the stop collects.
private const val LAST = "the note after the restart"

// why: a note handed over twice comes with a collection after the last note's, a look or two later; two seconds is ten
// looks of these machines.
private const val LATE_S = 2L

internal class NotesAcrossRestartTest : TwoMachinesTest() {

    @Test
    fun `a stop while a batch of notes is handed over hands none of them over twice`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val links = links(joined().second)
        // torad mirrors mini's session before mini's link runs, so the notes wait in its outbox for one collection.
        offered(links)
        listed(rig) { "$MINI/splice" in it }
        val sent = (1..BATCH).map { "note $it" }
        sent.forEach { rig.send("$MINI/splice", it) }

        val first = link(links)
        val heard = mutableListOf(checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "the batch began to arrive" }.text)
        runBlocking { first.cancelAndJoin() }
        link(links)
        rig.send("$MINI/splice", LAST)
        // Each note is its own SendMessage, so they reach the outbox in whatever order their connections are read.
        val expected = sent + LAST
        while (!heard.containsAll(expected)) {
            heard += checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "heard so far: $heard" }.text
        }
        generateSequence { splice.heard(LATE_S)?.text }.forEach { heard += it }

        assertEquals(expected.sorted(), heard.sorted(), "each note once")
    }
}
