// NEW: retained message metadata parses once per settled line, not once per Sessions poll.
package splice.sessions.activity

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.storage.DayLine
import splice.core.storage.LineFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: small metadata gets at most 16 MiB, independently of retention or a runaway writer.
internal const val EDGE_CACHE_BYTES: Long = 16 * 1024 * 1024L

// why: map entry, edge object, recipient wrapper and list references beyond separately weighed text.
private const val EDGE_ENTRY_BYTES = 256L

// why: file identity, cursor, map, boundary fingerprints and tail bookkeeping.
private const val EDGE_FILE_BYTES = 512L

// why: enough boundary bytes to recognize changed small metadata rows without retaining their JSON.
private const val EDGE_BOUNDARY_BYTES = 64

// why: transient decoded JSON and UTF-16 text, before only the small edge fields survive.
private const val EDGE_PARSE_EXPANSION = 8L

internal class MessageEdgeCache(
    private val days: ActivityDays,
    private val files: DayFiles,
    private val decode: MessageEdgeDecode,
    private val heap: HeapReservations?,
    private val maxBytes: Long,
) {
    private val kept = linkedMapOf<Any, Kept>()
    private var answer: List<MessageEdge> = emptyList()
    private var lastKeys: Set<Any> = emptySet()

    @Synchronized
    fun edges(since: Long? = null): List<MessageEdge> {
        // A day file last written before [since] holds no edge at or after it, so a caller that asks for a span
        // neither reads nor keeps the days before it.
        val opened = days.retainedFiles().mapNotNull { path -> attributes(path)?.let { path to it } }
            .filter { (_, attrs) -> writtenSince(attrs, since) }
        val keys = opened.mapTo(HashSet()) { (path, attrs) -> identity(path, attrs) }
        var changed = kept.keys.retainAll(keys) || keys != lastKeys
        lastKeys = keys
        val states = opened.mapNotNull { (path, attrs) ->
            files.open(path) { file ->
                val key = identity(path, attrs)
                val before = kept[key]
                val reusable = before?.takeIf { known(file, it, attrs) }
                val state = reusable ?: Kept(heap).also {
                    ensure(EDGE_FILE_BYTES)
                    kept[key] = it
                }
                if (state !== before || file.size != state.size) {
                    update(file, state)
                    state.size = file.size
                    state.modified = attrs["lastModifiedTime"] as? FileTime
                    changed = true
                }
                state
            }
        }
        if (changed) answer = collect(states)
        return answer
    }

    private fun writtenSince(attrs: Map<String, Any>, since: Long?): Boolean {
        val written = (attrs["lastModifiedTime"] as? FileTime)?.toMillis()
        return since == null || written == null || written >= since
    }

    private fun attributes(path: Path): Map<String, Any>? = try {
        Files.readAttributes(
            path,
            "basic:fileKey,creationTime,lastModifiedTime,isRegularFile",
            LinkOption.NOFOLLOW_LINKS,
        ).takeIf { it["isRegularFile"] == true }
    } catch (_: NoSuchFileException) {
        null
    }

    private fun identity(path: Path, attrs: Map<String, Any>): Any =
        attrs["fileKey"] ?: path to attrs["creationTime"]

    private fun known(file: LineFile, state: Kept, attrs: Map<String, Any>): Boolean {
        if (file.size < state.size) return false
        if (file.size == state.size && attrs["lastModifiedTime"] != state.modified) return false
        return file.bytes(0L, state.first.size).contentEquals(state.first) &&
            file.bytes(state.end - state.last.size, state.last.size).contentEquals(state.last)
    }

    private fun update(file: LineFile, state: Kept) {
        val settled = file.settledEnd()
        val added = linkedMapOf<String, MessageEdge>()
        val staged = heap?.let { it.reserve(0L) ?: throw HeapCapacityException() }
        try {
            readAdded(file, state, settled, added, staged)
            added.values.toList().asReversed().forEach { edge ->
                if (edge.id !in state.rows) {
                    grow(state, EDGE_ENTRY_BYTES + textBytes(edge))
                    state.rows[edge.id] = edge
                }
            }
        } finally {
            staged?.close()
        }
        val tail = if (settled == file.size) null else tail(file, settled, state.tail)
        val tailWeight = tail?.let { it.bytes.size.toLong() * 2 + (it.edge?.let(::textBytes) ?: 0L) } ?: 0L
        grow(state, tailWeight - state.tailWeight)
        state.tailWeight = tailWeight
        state.tail = tail
        state.end = settled
        val count = minOf(settled, EDGE_BOUNDARY_BYTES.toLong()).toInt()
        state.first = file.bytes(0L, count)
        state.last = file.bytes(settled - count, count)
    }

    private fun readAdded(
        file: LineFile,
        state: Kept,
        settled: Long,
        added: MutableMap<String, MessageEdge>,
        lease: HeapLease?,
    ) {
        var weight = 0L
        val _ = file.lines(state.end, settled) { line ->
            val edge = if (matches(line, state.tail)) state.tail?.edge else parse(line)
            if (edge != null) {
                val old = added.remove(edge.id)
                weight += EDGE_ENTRY_BYTES + textBytes(edge)
                if (old != null) weight -= EDGE_ENTRY_BYTES + textBytes(old)
                ensure(weight)
                if (lease?.resize(weight) == false) throw HeapCapacityException()
                // Moving a repeated id preserves its oldest position when reversed.
                added[edge.id] = edge
            }
            true
        }
    }

    private fun matches(line: DayLine, tail: Tail?): Boolean {
        if (tail == null) return false
        val bodySize = tail.bytes.size - if (tail.bytes.lastOrNull() == '\r'.code.toByte()) 1 else 0
        if (line.byteSize != bodySize.toLong()) return false
        val bytes = line.bytes().use { it.readBytes() }
        return bytes.indices.all { bytes[it] == tail.bytes[it] }
    }

    private fun tail(file: LineFile, settled: Long, before: Tail?): Tail {
        ensure(file.size - settled)
        val bytes = file.bytes(settled, (file.size - settled).toInt())
        if (before != null && bytes.contentEquals(before.bytes)) return before
        var edge: MessageEdge? = null
        val _ = file.lines(settled, file.size) { line ->
            edge = parse(line)
            true
        }
        return Tail(bytes, edge)
    }

    private fun parse(line: DayLine): MessageEdge? {
        ensure(line.byteSize * EDGE_PARSE_EXPANSION)
        val lease = heap?.let { it.reserve(line.byteSize * EDGE_PARSE_EXPANSION) ?: throw HeapCapacityException() }
        return try {
            decode.parse(line.text())
        } finally {
            lease?.close()
        }
    }

    private fun grow(state: Kept, extra: Long) {
        ensure(extra)
        val next = state.weight + extra
        if (state.lease?.resize(next) == false) throw HeapCapacityException()
        state.weight = next
    }

    private fun ensure(extra: Long) {
        if (extra > maxBytes - kept.values.sumOf { it.weight }) {
            throw IOException("retained message-edge metadata exceeds its $maxBytes byte allowance")
        }
    }

    private fun textBytes(edge: MessageEdge): Long =
        2L * (edge.from.length + edge.to.length + edge.id.length + (edge.toSession?.length ?: 0))

    private fun collect(states: List<Kept>): List<MessageEdge> {
        val unique = linkedMapOf<String, MessageEdge>()
        states.forEach { state ->
            state.rows.values.forEach { edge -> unique.putIfAbsent(edge.id, edge) }
            state.tail?.edge?.let { unique.putIfAbsent(it.id, it) }
        }
        val result = unique.values.toList()
        heap?.let { budget ->
            val _ = HeapOwners.charge(result, budget, result.size * EDGE_ENTRY_BYTES)
            states.forEach { state -> state.lease?.let { HeapOwners.keep(result, it.share()) } }
        }
        return result
    }

    private class Kept(heap: HeapReservations?) {
        val rows = linkedMapOf<String, MessageEdge>()
        val lease: HeapLease? = heap?.let { HeapOwners.charge(this, it, EDGE_FILE_BYTES) }
        var weight = EDGE_FILE_BYTES
        var end = 0L
        var size = -1L
        var modified: FileTime? = null
        var first = byteArrayOf()
        var last = byteArrayOf()
        var tail: Tail? = null
        var tailWeight = 0L
    }

    private data class Tail(val bytes: ByteArray, val edge: MessageEdge?)
}
