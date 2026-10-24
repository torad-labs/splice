// NEW: LAN share, phase 2, round-1 review. The goal: a note a session here writes to a joined machine's mirror reaches
// that machine's session, once and in order, whatever happens to the collections that carry it. A collection whose
// answer never arrives (the machine slept, its daemon restarted, its Wi-Fi dropped) takes nothing with it: a note leaves
// the outbox only once a later collection acknowledges it. Checked against a model over generated runs, since no list
// of examples covers the orders in which notes, cut collections and acknowledged ones can come. Round 12 of PR #419's
// review added a revoke on the Sharing page followed by a re-join: what waited at the revoke goes, and every note after
// it arrives, though the collecting machine still carries its acknowledgement of the box the revoke threw away. Round 13
// added the collecting machine's own restart: what it acknowledged is kept where its daemon keeps it (RemoteAcks), so
// what it was handed before the restart is never handed again.
package splice.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.mirror.MirroredSession
import splice.share.remote.RemoteAcks
import java.nio.file.Path
import kotlin.random.Random

// why: the joined machine whose notes these are.
private const val MINI = "mini"

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

    private fun ShareOutboxes.collected(collector: Collector): ShareOutbox =
        runBlocking { collect(MINI, collector.ack, 0) }

    @Test
    fun `every note held for a machine reaches it once and in order, whatever collections are cut on the way`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            var outboxes = ShareOutboxes()
            val acks = tmp.resolve("acks-$run.json")
            var collector = Collector(RemoteAcks(acks) { })
            val expected = mutableListOf<String>()
            repeat(random.nextInt(1, ACTIONS_AT_MOST)) { step ->
                val draw = random.nextInt(DRAWS)
                when {
                    draw < NOTE_UNDER -> {
                        val text = "run $run, note $step"
                        val carried = runBlocking { outboxes.carrier(MINI).carry(to, from, text) }
                        assertEquals(null, carried.status, text)
                        expected += text
                    }
                    // The answer never reaches the collecting machine: it slept, restarted or lost its network.
                    draw < CUT_UNDER -> outboxes.collected(collector)
                    draw < DELIVERED_UNDER -> collector.took(outboxes.collected(collector))
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
                        expected.retainAll(collector.delivered.toSet())
                    }
                }
            }
            do {
                val answer = outboxes.collected(collector)
                collector.took(answer)
            } while (answer.notes.isNotEmpty())
            assertEquals(expected, collector.delivered, "run $run")
        }
    }
}
