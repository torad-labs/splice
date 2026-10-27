package splice.sessions.peer.journal

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.SenderCheck
import splice.sessions.registry.SessionRecord
import java.nio.file.Path

private const val GARY = "uds:/run/user/1000/cc-socks/42.sock"
private const val ROSE = "uds:/run/user/1000/cc-socks/43.sock"

class PeerInboxRepeatTest {
    private val log = LogSink { }
    private val self = PeerSender("/run/user/1000/cc-socks/7.sock", "temple")
    private val gary = SenderCheck("gary", "s-gary", GARY, "gary", registered = true)
    private val rose = SenderCheck("rose", "s-rose", ROSE, "rose", registered = true)

    /** Ten seconds pass between frames, so the inbound limits never refuse one here. */
    private var now = 0L
    private val clock = ElapsedClock {
        now += STEP_MS
        now
    }

    /** The receipts the inbox wrote back, by msg_id, in order. */
    private class Receipts : PeerOutbox {
        val sent = mutableListOf<Pair<String, DeliveryStatus>>()

        override suspend fun send(
            target: SessionRecord,
            text: String,
            sender: PeerSender,
            msgId: String?,
        ): SendOutcome = SendOutcome.Submitted("unused")

        override suspend fun receipt(
            toAddress: String,
            origMsgId: String,
            status: DeliveryStatus,
            detail: String?,
            sender: PeerSender,
        ): Boolean {
            sent += origMsgId to status
            return true
        }
    }

    @Test
    fun `the same message again under its id is delivered as the first was and journaled once`(@TempDir dir: Path) {
        val receipts = Receipts()
        val journal = PeerJournal(dir, log)
        val inbox = PeerInbox(journal, receipts, self, log, clock)

        val first = runBlocking { inbox.onMessage(InboundFrame.Message("m-1", GARY, "gary", "run the gate"), gary) }
        val again = runBlocking { inbox.onMessage(InboundFrame.Message("m-1", GARY, "gary", "run the gate"), gary) }

        assertEquals(DeliveryStatus.DELIVERED, first)
        assertEquals(DeliveryStatus.DELIVERED, again)
        assertEquals(listOf("m-1" to DeliveryStatus.DELIVERED, "m-1" to DeliveryStatus.DELIVERED), receipts.sent)
        assertEquals(1, messages(journal).size)
    }

    @Test
    fun `another text or another sender under an id already taken is refused and not journaled`(@TempDir dir: Path) {
        val journal = PeerJournal(dir, log)
        val inbox = PeerInbox(journal, Receipts(), self, log, clock)

        runBlocking { inbox.onMessage(InboundFrame.Message("m-1", GARY, "gary", "run the gate"), gary) }
        val changed = runBlocking { inbox.onMessage(InboundFrame.Message("m-1", GARY, "gary", "skip the gate"), gary) }
        val stranger = runBlocking { inbox.onMessage(InboundFrame.Message("m-1", ROSE, "rose", "run the gate"), rose) }

        assertEquals(DeliveryStatus.REFUSED, changed)
        assertEquals(DeliveryStatus.REFUSED, stranger)
        assertEquals(listOf("run the gate"), messages(journal).map { JsonScalars.str(it, "text") })
    }

    @Test
    fun `a helper that starts again knows every id its journal holds, past the last thousand`(@TempDir dir: Path) {
        val before = PeerInbox(PeerJournal(dir, log), Receipts(), self, log, clock)
        runBlocking {
            before.onMessage(InboundFrame.Message("m-first", GARY, "gary", "the first"), gary)
            for (n in 1..MANY) before.onMessage(InboundFrame.Message("m-$n", GARY, "gary", "number $n"), gary)
        }
        val journal = PeerJournal(dir, log)
        val after = PeerInbox(journal, Receipts(), self, log, clock)

        val repeat = runBlocking { after.onMessage(InboundFrame.Message("m-first", GARY, "gary", "the first"), gary) }
        val reused = runBlocking { after.onMessage(InboundFrame.Message("m-first", GARY, "gary", "another"), gary) }

        assertEquals(DeliveryStatus.DELIVERED, repeat)
        assertEquals(DeliveryStatus.REFUSED, reused)
        assertEquals(MANY + 1L, journal.counts().total)
    }

    private fun messages(journal: PeerJournal) = journal.read(0, PAGE, setOf("message")).items

    private companion object {
        // why: past the thousand ids the journal used to remember, so the test shows the whole journal counts.
        const val MANY = 1_100

        // why: one page of the journal, enough for the few records the first two tests write.
        const val PAGE = 50

        // why: ten seconds between frames refills the sender's inbound bucket before every one.
        const val STEP_MS = 10_000L
    }
}
