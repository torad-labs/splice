// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2.3): the reads over a peer's two journal files, the live one and the
// rolled one. Each read is a scan of the files, so the 50 MiB roll bounds its cost. The live file is read first: a roll
// between the two reads can show a record twice, never lose one, and every read here answers by seq.
package splice.sessions.peer.journal

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.TreeMap

// why: the most records one read returns, so one inbox answer stays a few hundred KB at most.
internal const val PEER_JOURNAL_READ_MAX = 200

// why: spec 4.3, a message whose msg_id is among the last 1,000 journaled messages is a repeat.
internal const val PEER_JOURNAL_RECENT_IDS = 1000

internal class JournalScan(private val live: Path, private val rolled: Path) {

    /** The highest seq on disk, and the ids of the newest messages, oldest first. */
    data class Loaded(val lastSeq: Long, val recentIds: List<String>)

    private fun interface Visit {
        fun on(seq: Long, record: JsonObject)
    }

    /** The records after [after], at most [limit], of [kinds], in seq order. */
    fun read(after: Long, limit: Int, kinds: Set<String>): PeerJournal.Page {
        val wanted = limit.coerceIn(1, PEER_JOURNAL_READ_MAX)
        val found = TreeMap<Long, JsonObject>()
        for (file in listOf(live, rolled)) {
            each(file) { seq, record ->
                if (seq > after && JsonScalars.str(record, PEER_KEY_KIND) in kinds) {
                    found[seq] = record
                    trim(found, wanted)
                }
            }
        }
        return PeerJournal.Page(found.values.toList(), if (found.isEmpty()) after else found.lastKey())
    }

    /** Every message, and those after [after], in one pass. */
    fun count(after: Long): PeerJournal.Counts {
        var total = 0L
        var unread = 0L
        var liveFloor = Long.MAX_VALUE
        each(live) { seq, record ->
            liveFloor = minOf(liveFloor, seq)
            if (JournalLines.isKind(record, PEER_KIND_MESSAGE)) {
                total++
                if (seq > after) unread++
            }
        }
        each(rolled) { seq, record ->
            if (seq < liveFloor && JournalLines.isKind(record, PEER_KIND_MESSAGE)) {
                total++
                if (seq > after) unread++
            }
        }
        return PeerJournal.Counts(total, unread)
    }

    /** The newest receipt that came back for the send [origMsgId], or null when none did. */
    fun receiptFor(origMsgId: String): PeerJournal.Receipt? {
        var newest = 0L
        var found: PeerJournal.Receipt? = null
        for (file in listOf(live, rolled)) {
            each(file) { seq, record ->
                if (seq > newest && matches(record, origMsgId)) {
                    newest = seq
                    found = receiptOf(record)
                }
            }
        }
        return found
    }

    /** The highest seq on disk, or 0 when the journal is empty. */
    fun lastSeq(): Long {
        for (file in listOf(live, rolled)) {
            val seq = JournalLines.tail(file).asReversed().firstNotNullOfOrNull { line -> JournalLines.seqIn(line) }
            if (seq != null) return seq
        }
        return 0L
    }

    /** `pushed` or `failed` for the newest webhook record in the tail of either file, or null when there is none. */
    fun lastWebhookOutcome(): String? {
        for (file in listOf(live, rolled)) {
            val outcome = JournalLines.tail(file).asReversed().firstNotNullOfOrNull { JournalLines.outcomeIn(it) }
            if (outcome != null) return outcome
        }
        return null
    }

    /** The highest seq and the newest message ids, from the rolled file to the live one. */
    fun load(): Loaded {
        var last = 0L
        val ids = ArrayDeque<String>()
        for (file in listOf(rolled, live)) {
            each(file) { seq, record ->
                last = maxOf(last, seq)
                val id = JsonScalars.str(record, PEER_KEY_ID)
                if (id != null && JournalLines.isKind(record, PEER_KIND_MESSAGE)) remember(ids, id)
            }
        }
        return Loaded(last, ids.toList())
    }

    /** A cheap reading of both files' size and age, to tell whether anything was appended since the last one. */
    fun stamp(): String = "${JournalLines.stat(live)}|${JournalLines.stat(rolled)}"

    private fun each(file: Path, visit: Visit) {
        val input = try {
            Files.newInputStream(file)
        } catch (_: NoSuchFileException) {
            return
        }
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                val record = JournalLines.parse(line)
                val seq = record?.let { JournalLines.seqOf(it) }
                if (record != null && seq != null) visit.on(seq, record)
            }
        }
    }

    private fun trim(found: TreeMap<Long, JsonObject>, wanted: Int) {
        while (found.size > wanted) {
            found.pollLastEntry()
        }
    }

    private fun remember(ids: ArrayDeque<String>, id: String) {
        ids.addLast(id)
        if (ids.size > PEER_JOURNAL_RECENT_IDS) ids.removeFirst()
    }

    private fun matches(record: JsonObject, origMsgId: String): Boolean =
        JournalLines.isKind(record, PEER_KIND_RECEIPT) && JsonScalars.str(record, "origMsgId") == origMsgId

    private fun receiptOf(record: JsonObject): PeerJournal.Receipt = PeerJournal.Receipt(
        status = JsonScalars.str(record, "status").orEmpty(),
        detail = JsonScalars.str(record, "detail"),
        at = JsonScalars.long(record, "at") ?: 0L,
    )
}
