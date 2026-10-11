// NEW: response offsets and exact preceding-message counts, indexed without decoding historical payloads.
package splice.client.transcript

import splice.core.util.FileIdentity
import splice.core.util.FileStat
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptReadBudget
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: retain recent request histories without keeping every opened transcript's metadata in memory.
private const val RESPONSE_INDEX_FILES = 8

// why: fits the scale fixture's 60,001 replies while bounding each file; evicted old ids rescan, never claim absence.
private const val RESPONSE_INDEX_POINTS = 65_536

// why: the sent-text index uses this same 4 KiB old-EOF witness before trusting a file's growth as append.
private const val RESPONSE_APPEND_GUARD = 4_096

internal data class TranscriptReplyPoint(val start: Long, val end: Long, val before: Long, val messages: Int)

internal sealed class IndexedReply {
    data class Found(val point: TranscriptReplyPoint) : IndexedReply()
    data object Missing : IndexedReply()
    data object BudgetSpent : IndexedReply()
}

/** Different files have independent locks. Cache bookkeeping holds no lock while a file is read. */
internal class TranscriptResponseIndex(private val opener: TranscriptOpener) {
    private val entries = LinkedHashMap<Path, ResponseFileIndex>()

    fun find(file: Path, responseId: String, budget: TranscriptReadBudget): IndexedReply {
        val key = file.toRealPath()
        val entry = synchronized(entries) {
            val active = entries.remove(key) ?: ResponseFileIndex(opener)
            entries[key] = active
            if (entries.size > RESPONSE_INDEX_FILES) entries.remove(entries.keys.first())
            active
        }
        return entry.find(key, responseId, budget)
    }
}

private data class ResponseStamp(val size: Long, val modified: FileTime, val identity: FileIdentity?)

private class ResponseFileIndex(private val opener: TranscriptOpener) {
    private var stamp: ResponseStamp? = null
    private var guard = byteArrayOf()
    private var resume = 0L
    private var before = 0L
    private var points = LinkedHashMap<String, TranscriptReplyPoint>()
    private var dropped = false

    private fun stamp(file: Path): ResponseStamp {
        val attributes = FileStat(file, "size,lastModifiedTime")
        return ResponseStamp(
            attributes["size"] as Long,
            attributes["lastModifiedTime"] as FileTime,
            attributes.identity,
        )
    }

    @Synchronized
    fun find(file: Path, id: String, budget: TranscriptReadBudget): IndexedReply {
        if (!budget.hasTime()) return IndexedReply.BudgetSpent
        val next = stamp(file)
        if (stamp != next) refresh(file, next, id, budget)?.let { return it }
        val point = points[id]
        return when {
            dropped -> search(file, next, id, budget) // Eviction cannot prove that a cached id had no earlier reply.
            point != null -> IndexedReply.Found(point)
            else -> IndexedReply.Missing
        }
    }

    private fun refresh(file: Path, next: ResponseStamp, id: String, budget: TranscriptReadBudget): IndexedReply? {
        val old = stamp
        val appended = old != null && append(file, old, next)
        val scan = ReplyScan(if (appended) resume else 0L, if (appended) before else 0L, id)
        if (appended) {
            scan.points.putAll(points.filterValues { it.start < resume })
            scan.dropped = dropped
        }
        if (!scan.read(file, next.size, opener, budget)) return IndexedReply.BudgetSpent
        points = scan.points
        dropped = scan.dropped
        resume = scan.resume
        before = scan.beforeResume
        stamp = next
        guard = opener.open(file, (next.size - RESPONSE_APPEND_GUARD).coerceAtLeast(0L)).use {
            it.readNBytes(minOf(next.size, RESPONSE_APPEND_GUARD.toLong()).toInt())
        }
        return scan.selected?.let(IndexedReply::Found)
    }

    private fun search(file: Path, next: ResponseStamp, id: String, budget: TranscriptReadBudget): IndexedReply {
        val search = ReplyScan(0L, 0L, id)
        return if (search.read(file, next.size, opener, budget)) {
            search.selected?.let(IndexedReply::Found) ?: IndexedReply.Missing
        } else {
            IndexedReply.BudgetSpent
        }
    }

    private fun append(file: Path, old: ResponseStamp, next: ResponseStamp): Boolean =
        next.size > old.size && next.identity == old.identity &&
            opener.open(file, old.size - guard.size).use { it.readNBytes(guard.size).contentEquals(guard) }
}

/** Only the latest unfinished group is reindexed after append, so a new block can extend its old reply. */
private class ReplyScan(from: Long, initial: Long, private val wanted: String) {
    val points = LinkedHashMap<String, TranscriptReplyPoint>()
    var dropped = false
    var selected: TranscriptReplyPoint? = null
        private set
    var resume = from
        private set
    var beforeResume = initial
        private set
    private var seen = initial
    private val shape = TranscriptRecordShape()
    private val assembly = PageAssembly(0L, Int.MAX_VALUE, TranscriptRedaction(), byOffset = true)

    fun read(file: Path, size: Long, opener: TranscriptOpener, budget: TranscriptReadBudget): Boolean {
        opener.open(file, resume).use { input ->
            val lines = TranscriptLineReader(input, resume, size)
            var line = lines.next()
            while (line != null) {
                if (!budget.hasTime()) return false
                assembly.at = line.offset
                assembly.accept(line.bytes?.let(shape::read))
                val completed = assembly.drain()
                collect(completed, line.offset)
                remember(line.offset, completed)
                line = lines.next()
            }
        }
        finish(size)
        return true
    }

    private fun finish(end: Long) {
        val completed = assembly.finish()
        collect(completed, end)
        val pending = completed.lastOrNull()?.messageId?.let(points::get)
        if (pending?.end == end) {
            resume = pending.start
            beforeResume = pending.before
        }
    }

    private fun remember(offset: Long, completed: List<TranscriptMessage>) {
        val pending = assembly.pendingOffset
        if (pending == offset) {
            resume = offset
            beforeResume = seen
        } else if (pending == null) {
            resume = offset
            beforeResume = seen - completed.count { it.index / PER_RECORD == offset }
        }
    }

    private fun collect(messages: List<TranscriptMessage>, end: Long) {
        for (message in messages) {
            if (message.role == TranscriptRole.ASSISTANT) {
                message.messageId?.let { record(it, message.index / PER_RECORD, end) }
            }
            seen++
        }
    }

    private fun record(id: String, start: Long, end: Long) {
        val previous = if (id == wanted) selected ?: points[id] else points[id]
        val point = when {
            previous == null -> TranscriptReplyPoint(start, end, seen, 1)
            previous.before + previous.messages == seen -> previous.copy(end = end, messages = previous.messages + 1)
            else -> previous // The first contiguous reply with this id is the existing lookup's answer.
        }
        points[id] = point
        if (id == wanted) selected = point
        if (points.size > RESPONSE_INDEX_POINTS) {
            points.remove(points.keys.first())
            dropped = true
        }
    }
}
