// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2.3 and 7): a peer's journal. The helper appends the messages it
// receives, the receipts that come back for its sends and the outcome of each webhook push, each as one flushed
// line with the next seq. The daemon reads the same two files for the inbox routes and writes only read.cursor. The
// journal rolls at 50 MiB: journal.jsonl becomes journal.1.jsonl, and the seq goes on across the roll.
package splice.sessions.peer.journal

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

// why: spec 2.3, journal.jsonl is renamed to journal.1.jsonl when it reaches 50 MiB.
private const val PEER_JOURNAL_ROTATE_BYTES = 50L * 1024 * 1024

private const val PEER_JOURNAL_LIVE_FILE = "journal.jsonl"
private const val PEER_JOURNAL_ROLLED_FILE = "journal.1.jsonl"

/**
 * One peer's journal, in [dir] (`<state dir>/peers/<name>`). The writes are internal to the package: the helper's
 * PeerInbox and WebhookPusher make them, in one process. Any process may read.
 */
public class PeerJournal(
    dir: Path,
    private val log: LogSink,
    private val wallClock: WallClock = WallClock { System.currentTimeMillis() },
    private val rotateBytes: Long = PEER_JOURNAL_ROTATE_BYTES,
) {
    /** The records as journaled, and the seq to read after next. */
    public data class Page(val items: List<JsonObject>, val next: Long)

    /** Every message in the journal, and those past the read cursor. */
    public data class Counts(val total: Long, val unread: Long)

    /** A receipt that came back for one of this peer's sends. */
    public data class Receipt(val status: String, val detail: String?, val at: Long)

    private data class Counted(val stamp: String, val counts: Counts)

    private fun interface RecordLine {
        fun at(seq: Long, now: Long): String
    }

    private val name: String = dir.fileName?.toString().orEmpty()
    private val live: Path = dir.resolve(PEER_JOURNAL_LIVE_FILE)
    private val rolled: Path = dir.resolve(PEER_JOURNAL_ROLLED_FILE)
    private val files = PeerJournalFiles(dir)
    private val scan = JournalScan(live, rolled)
    private val ioLock = Any()
    private val recent = LinkedHashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The codex peer's own wake: a conflated channel wakes one receiver, and the webhook pusher holds [wake]. */
    private val codexWake = Channel<Unit>(Channel.CONFLATED)
    private var nextSeq: Long = 0L
    private var prepared: Boolean = false

    @Volatile
    private var counted: Counted? = null

    internal val cursors = JournalCursors(dir, files)

    /** The records after [after], at most [limit], of [kinds] (message, receipt, webhook), in seq order. */
    public fun read(after: Long, limit: Int, kinds: Set<String>): Page = scan.read(after, limit, kinds)

    /** The message count and the unread count. Unchanged files and cursor answer from the last count. */
    public fun counts(): Counts {
        val cursor = cursors.read()
        val stamp = "${scan.stamp()}|$cursor"
        val held = counted
        if (held != null && held.stamp == stamp) return held.counts
        val fresh = scan.count(cursor)
        counted = Counted(stamp, fresh)
        return fresh
    }

    public fun unread(): Long = counts().unread

    /** Moves the read cursor to [through], at most the newest seq and never backwards; the cursor as it then stands. */
    public fun ackThrough(through: Long): Long {
        val held = cursors.read()
        val next = maxOf(held, minOf(through, scan.lastSeq()))
        if (next != held) cursors.advanceRead(next)
        return next
    }

    /** The newest receipt that came back for the send [origMsgId], or null when none did. */
    public fun receiptFor(origMsgId: String): Receipt? = scan.receiptFor(origMsgId)

    /** `pushed` or `failed` for the newest finished webhook push, or null when none is in the journal's tail. */
    public fun lastWebhookOutcome(): String? = scan.lastWebhookOutcome()

    /** Forgets where the webhook stopped, so its next pusher starts at the journal's end. */
    public fun resetWebhookCursor() {
        if (cursors.resetWebhook()) log("[peers] ${LogSafe.str(name)}: the webhook cursor was reset")
    }

    /** The new message's seq, or null when its id is among the last 1,000 messages and nothing is written. */
    internal fun appendMessage(entry: JournalRecords.MessageEntry): Long? = synchronized(ioLock) {
        loadOnce()
        if (entry.id in recent) {
            null
        } else {
            val seq = commit(RecordLine { n, now -> JournalRecords.message(n, now, entry) })
            if (recent.add(entry.id) && recent.size > PEER_JOURNAL_RECENT_IDS) recent.remove(recent.first())
            wake.trySend(Unit)
            codexWake.trySend(Unit)
            seq
        }
    }

    internal fun appendReceipt(entry: JournalRecords.ReceiptEntry): Long = synchronized(ioLock) {
        loadOnce()
        commit(RecordLine { n, now -> JournalRecords.receipt(n, now, entry) })
    }

    internal fun appendWebhook(entry: JournalRecords.WebhookEntry): Long = synchronized(ioLock) {
        loadOnce()
        commit(RecordLine { n, now -> JournalRecords.webhook(n, now, entry) })
    }

    /** Suspends until a message has been appended since the last call returned. The webhook pusher and a codex peer's
     *  hand-over ([forCodex]) each wait on their own signal. */
    internal suspend fun awaitMessage(forCodex: Boolean = false) {
        (if (forCodex) codexWake else wake).receive()
    }

    internal fun appendCodex(entry: JournalRecords.CodexEntry): Long = synchronized(ioLock) {
        loadOnce()
        commit(RecordLine { n, now -> JournalRecords.codex(n, now, entry) })
    }

    /** The reads over the journal's files, for a reader that needs one [PeerJournal] does not offer. */
    internal val scanner: JournalScan get() = scan

    /** Writes the next record, rolling the journal first when it would pass the size; the record's seq. */
    private fun commit(line: RecordLine): Long {
        if (!prepared) {
            files.prepare()
            prepared = true
        }
        val seq = nextSeq
        val encoded = line.at(seq, wallClock())
        val size = if (Files.exists(live)) Files.size(live) else 0L
        val incoming = encoded.toByteArray(Charsets.UTF_8).size + 1
        if (size > 0L && size + incoming > rotateBytes) {
            files.roll(live, rolled)
            log("[peers] ${LogSafe.str(name)}: the journal rolled")
        }
        files.append(live, encoded, Long.MAX_VALUE)
        nextSeq = seq + 1
        return seq
    }

    private fun loadOnce() {
        if (nextSeq > 0L) return
        val loaded = scan.load()
        nextSeq = loaded.lastSeq + 1
        recent += loaded.recentIds
    }
}
