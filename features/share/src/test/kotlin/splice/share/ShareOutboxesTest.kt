// NEW: LAN share, phase 2, round-1 review. The goal: a note a session here writes to a joined machine's mirror reaches
// that machine's session, once and in order, whatever happens to the collections that carry it. A collection whose
// answer never arrives (the machine slept, its daemon restarted, its Wi-Fi dropped) takes nothing with it: a note leaves
// the outbox only once a later collection acknowledges it. Checked against a model over generated runs, since no list
// of examples covers the orders in which notes, cut collections and acknowledged ones can come. Round 12 of PR #419's
// review added a revoke on the Sharing page followed by a re-join: what waited at the revoke goes, and every note after
// it arrives, though the collecting machine still carries its acknowledgement of the box the revoke threw away. Round 13
// added the collecting machine's own restart: what it acknowledged is kept where its daemon keeps it (RemoteAcks), so
// what it was handed before the restart is never handed again. Round 14 made each join a machine of its own: what the
// mirrors of a revoked machine carry never reaches the machine that joins next under its name, whichever comes first.
package splice.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.mirror.MirroredSession
import splice.sessions.peer.DeliveryStatus
import splice.share.remote.RemoteAcks
import java.nio.file.Path
import kotlin.random.Random

// why: the joined machine whose notes these are.
private const val MINI = "mini"

// why: when mini joined, which names its join: a machine that joins again under its name is another join.
private const val JOINED = "2026-10-24T07:00:00Z"

// why: when a machine joined under mini's name after mini was revoked.
private const val JOINED_AGAIN = "2026-10-24T07:05:00Z"

// why: a fixed seed, so a failing run is the same run next time.
private const val SEED = 419

// why: enough runs that every order of the five actions comes up many times.
private const val RUNS = 300

// why: the most actions in one run, kept under the outbox's capacity of 256 so no note is refused as dropped.
private const val ACTIONS_AT_MOST = 200

// why: the share of each action in a draw of twelve: a note, a cut collection, a delivered one, a restart of the sharing
// machine, a restart of the collecting one, a revoke.
private const val DRAWS = 12
private const val NOTE_UNDER = 5
private const val CUT_UNDER = 7
private const val DELIVERED_UNDER = 9
private const val RESTART_UNDER = 10
private const val COLLECTOR_RESTART_UNDER = 11

class ShareOutboxesTest {
    @TempDir
    lateinit var tmp: Path

    private val to = MirroredSession("s-mini", "splice")
    private val from = MirroredSession("s-torad", "rig-lead")

    /** The collecting machine's daemon: the acknowledgement its next collection carries, kept in [acks] as the daemon
     *  keeps it, and what its sessions were handed, [delivered], which outlives the daemon. */
    private class Collector(private val acks: RemoteAcks, val delivered: MutableList<String> = mutableListOf()) {
        val ack: ShareOutboxAck? get() = acks.of(MINI)

        /** Delivers [answer]'s notes, and acknowledges them with its next collection. */
        fun took(answer: ShareOutbox) {
            answer.notes.forEach { delivered += it.text }
            answer.acknowledging()?.let { acks.delivered(MINI, it) }
        }
    }

    private fun ShareOutboxes.collected(collector: Collector, joined: String): ShareOutbox =
        runBlocking { collect(MINI, joined, collector.ack, 0) }

    @Test
    fun `every note held for a machine reaches it once and in order, whatever collections are cut on the way`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            var outboxes = ShareOutboxes()
            val acks = tmp.resolve("acks-$run.json")
            var collector = Collector(RemoteAcks(acks) { })
            var joined = JOINED
            val expected = mutableListOf<String>()
            repeat(random.nextInt(1, ACTIONS_AT_MOST)) { step ->
                val draw = random.nextInt(DRAWS)
                when {
                    draw < NOTE_UNDER -> {
                        val text = "run $run, note $step"
                        val carried = runBlocking { outboxes.carrier(MINI, joined).carry(to, from, text) }
                        assertEquals(null, carried.status, text)
                        expected += text
                    }
                    // The answer never reaches the collecting machine: it slept, restarted or lost its network.
                    draw < CUT_UNDER -> outboxes.collected(collector, joined)
                    draw < DELIVERED_UNDER -> collector.took(outboxes.collected(collector, joined))
                    // The sharing machine's daemon restarts: what it held in memory is gone, and the collecting
                    // machine still carries its acknowledgement of the old outbox.
                    draw < RESTART_UNDER -> {
                        outboxes = ShareOutboxes()
                        expected.retainAll(collector.delivered.toSet())
                    }
                    // The collecting machine's daemon restarts: what it held in memory is gone, and what its sessions
                    // were handed stays handed.
                    draw < COLLECTOR_RESTART_UNDER -> collector = Collector(RemoteAcks(acks) { }, collector.delivered)
                    // Marcos revokes the collecting machine on the Sharing page and it joins again: the notes waiting
                    // for it go, and its collections carry on with the acknowledgement they held.
                    else -> {
                        outboxes.drop(MINI)
                        joined = "$JOINED_AGAIN, step $step"
                        expected.retainAll(collector.delivered.toSet())
                    }
                }
            }
            do {
                val answer = outboxes.collected(collector, joined)
                collector.took(answer)
            } while (answer.notes.isNotEmpty())
            assertEquals(expected, collector.delivered, "run $run")
        }
    }

    // A revoke races a note: a mirror of the revoked machine hands one over as the revoke lands, from the Sharing page
    // (which lets go of what waits) or from `splice share revoke` in a process of its own (which cannot), and a machine
    // joins under its name. Whichever comes first, the machine that joined is handed none of it, and a note carried
    // once that machine collects is refused in words.
    @Test
    fun `a note written for a revoked machine never reaches the machine that joins next under its name`() {
        val orders = mapOf<String, (ShareOutboxes) -> Unit>(
            "carried, then revoked on the page" to { outboxes ->
                outboxes.carriedBy(JOINED, "for mini")
                outboxes.drop(MINI)
            },
            "revoked on the page, then carried" to { outboxes ->
                outboxes.drop(MINI)
                outboxes.carriedBy(JOINED, "for mini")
            },
            "revoked by the CLI, then carried" to { outboxes -> outboxes.carriedBy(JOINED, "for mini") },
        )
        for ((order, revoked) in orders) {
            val outboxes = ShareOutboxes()
            revoked(outboxes)
            val next = runBlocking { outboxes.collect(MINI, JOINED_AGAIN, null, 0) }
            assertEquals(emptyList<String>(), next.notes.map { it.text }, "$order: the next machine is handed none")

            val late = outboxes.carriedBy(JOINED, "once the next one collected")
            assertEquals(DeliveryStatus.REFUSED, late.status, "$order: carried after, it is refused")
            assertTrue(late.detail.orEmpty().isNotBlank(), "$order: in words")
            outboxes.carriedBy(JOINED_AGAIN, "for the next one")
            val then = runBlocking { outboxes.collect(MINI, JOINED_AGAIN, null, 0) }
            assertEquals(listOf("for the next one"), then.notes.map { it.text }, "$order: its own notes reach it")
        }
    }

    private fun ShareOutboxes.carriedBy(joined: String, text: String) =
        runBlocking { carrier(MINI, joined).carry(to, from, text) }
}
