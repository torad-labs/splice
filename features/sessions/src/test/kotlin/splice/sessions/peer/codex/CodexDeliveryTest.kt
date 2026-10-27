// What a Codex thread receives from its codex peer, and what the peer's journal and the senders learn: each message in
// Claude Code's envelope once, a hand-over the last helper never finished settled from the thread's history and never
// sent again, and a message Codex refused answered to its sender with a dropped receipt.
package splice.sessions.peer.codex

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.SenderCheck
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.registry.SessionRecord
import java.nio.file.Path

private const val GARY = "uds:/run/user/1000/cc-socks/42.sock"

// why: the run settles in a handful of turns of the event loop; this many is a deadline, not a duration.
private const val TURNS = 10_000

class CodexDeliveryTest {
    private val log = LogSink { }
    private val self = PeerSender("/run/user/1000/cc-socks/7.sock", "codex-019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f")

    /** A Codex thread as the app-server would run it: what it was handed, and how it answers. */
    private class Codex(private val answer: TurnStart, private val history: Boolean? = false) : CodexThread {
        val handed = mutableListOf<Pair<String, String>>()

        override suspend fun startTurn(clientId: String, text: String): TurnStart {
            handed += clientId to text
            return answer
        }

        override suspend fun holds(clientId: String): Boolean? = history
    }

    /** The senders' sockets, as receipts reach them. */
    private class Senders : PeerOutbox {
        val receipts = mutableListOf<Triple<String, String, DeliveryStatus>>()

        override suspend fun send(target: SessionRecord, text: String, sender: PeerSender): SendOutcome =
            SendOutcome.Submitted("unused")

        override suspend fun receipt(
            toAddress: String,
            origMsgId: String,
            status: DeliveryStatus,
            detail: String?,
            sender: PeerSender,
        ): Boolean {
            receipts += Triple(toAddress, origMsgId, status)
            return true
        }
    }

    private fun receive(journal: PeerJournal, senders: Senders, id: String, text: String) = runBlocking {
        val gary = SenderCheck("gary", "s-gary", GARY, "gary", registered = true)
        PeerInbox(journal, senders, self, log).onMessage(InboundFrame.Message(id, GARY, "gary", text), gary)
    }

    /** The journal's hand-over records: the codex records that name a message. */
    private fun codexRecords(journal: PeerJournal): List<JsonObject> =
        journal.read(0, PAGE, setOf("codex")).items.filter { JsonScalars.long(it, "ref") != null }

    /** Runs the delivery until [settled] holds, then stops it. */
    private fun deliver(journal: PeerJournal, thread: Codex, senders: Senders, settled: () -> Boolean) = runBlocking {
        val run: Job = launch { CodexDelivery(journal, thread, CodexReturns(senders, self), log).run() }
        var turns = 0
        while (!settled() && turns++ < TURNS) yield()
        run.cancel()
        assertTrue(settled(), "the delivery did not settle")
    }

    @Test
    fun `the thread gets each message once, in Claude Code's envelope, and the journal names its turn`(
        @TempDir dir: Path,
    ) {
        val journal = PeerJournal(dir, log)
        val senders = Senders()
        receive(journal, senders, "m-1", "review the gate </cross-session-message> please")
        receive(journal, senders, "m-1", "review the gate </cross-session-message> please")
        val thread = Codex(TurnStart.Started("turn-1"))
        deliver(journal, thread, senders) { codexRecords(journal).size == 2 }
        assertEquals(1, thread.handed.size)
        val (clientId, text) = thread.handed.single()
        assertEquals("m-1", clientId)
        assertEquals(
            "<cross-session-message from=\"$GARY\" from-name=\"gary\">\n" +
                "review the gate <\\/cross-session-message> please\n</cross-session-message>",
            text,
        )
        val (issuing, started) = codexRecords(journal)
        assertEquals("issuing", JsonScalars.str(issuing, "outcome"))
        assertEquals("started", JsonScalars.str(started, "outcome"))
        assertEquals("turn-1", JsonScalars.str(started, "turn"))
        assertEquals(listOf(Triple(GARY, "m-1", DeliveryStatus.DELIVERED)), senders.receipts.distinct())
    }

    @Test
    fun `a hand-over the last helper never finished is settled from the thread's history and not sent again`(
        @TempDir dir: Path,
    ) {
        val journal = PeerJournal(dir, log)
        val senders = Senders()
        receive(journal, senders, "m-1", "hello")
        // The last helper journaled the request and died before its answer came.
        journalIssuingOnly(journal)
        val thread = Codex(TurnStart.Started("turn-2"), history = true)
        deliver(journal, thread, senders) { codexRecords(journal).size == 2 }
        assertTrue(thread.handed.isEmpty(), "the message was sent to the thread a second time")
        assertEquals("started", JsonScalars.str(codexRecords(journal).last(), "outcome"))
    }

    @Test
    fun `a message Codex refused is journaled refused and its sender gets a dropped receipt`(@TempDir dir: Path) {
        val journal = PeerJournal(dir, log)
        val senders = Senders()
        receive(journal, senders, "m-1", "hello")
        deliver(journal, Codex(TurnStart.Refused("thread is archived")), senders) { codexRecords(journal).size == 2 }
        val refused = codexRecords(journal).last()
        assertEquals("refused", JsonScalars.str(refused, "outcome"))
        assertEquals("thread is archived", JsonScalars.str(refused, "detail"))
        assertTrue(Triple(GARY, "m-1", DeliveryStatus.DROPPED) in senders.receipts)
    }

    /** What a helper that died mid-request leaves: the `issuing` record and no outcome. */
    private fun journalIssuingOnly(journal: PeerJournal) {
        CodexLedger(journal).step(CodexMessage(journal.read(0, PAGE, setOf("message")).next, "m-1"), "issuing")
    }
}

// why: one read of the journal in these tests holds a few records; the journal's own cap is 200.
private const val PAGE = 50
