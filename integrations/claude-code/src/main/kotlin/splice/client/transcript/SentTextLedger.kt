// NEW: V4-427 — hold only redacted SendMessage texts, never the conversation, against a file snapshot.
// A session's subagents send too, and Claude Code keeps their transcripts beside the session's own, in
// <session>/subagents/*.jsonl (often absolute symlinks): a text the session's file lacks is looked for there
// (marlin's 3:37 walk, Oct 12: ten of a team's messages were a subagent's).
// Unchanged files cost a stat and no transcript bytes. Appends resume at the last complete line;
// a replaced, shrunk or same-size rewritten file starts over. A boundary guard detects truncate-and-grow.
package splice.client.transcript

import splice.client.resume.TRANSCRIPT_SUFFIX
import splice.core.util.FileIdentity
import splice.core.util.FileStat
import splice.sessions.transcript.SentTexts
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: hold the recent sessions and their subagents (419 beside one session on his machine) without keeping every
// transcript ever visited; an entry is its texts and a 4 KiB tail, so this many cost a few megabytes.
private const val HELD_FILES = 1024

// why: where Claude Code writes a session's subagent transcripts, beside the session's own file.
private const val SUBAGENTS = "subagents"

// why: a bounded witness of the old EOF distinguishes append from the common truncate-and-grow rewrite.
private const val GUARD_BYTES = 4_096

// why: scan in blocks, not one synchronized InputStream call per transcript byte.
private const val SCAN_BYTES = 64 shl 10

// why: match the paged reader's line ceiling; oversized tool output is skipped, not retained in memory.
private const val SEND_LINE_BYTES = 32 shl 20

/** One shared send-text index per reader, used by both session edges and team chat. */
internal class SentTextLedger(
    private val opener: TranscriptOpener,
    private val collect: SentTextCollector,
) {
    private val held = LinkedHashMap<Path, Held>()

    /** [read] over a session's own transcript, then over its subagents' for the ids it lacks. The path named is the
     *  session's own file. */
    fun readSession(file: Path, ids: Set<String>): SentTexts {
        val own = read(file, ids)
        if (own.missing.isEmpty()) return own
        val found = HashMap(own.texts)
        for (agent in subagents(file)) {
            val left = ids - found.keys
            if (left.isEmpty()) break
            try {
                found.putAll(read(agent, left).texts)
            } catch (_: NoSuchFileException) {
                // removed between the listing and the read: it has no texts left to give
            }
        }
        return SentTexts(own.path, found, ids - found.keys)
    }

    /** The subagent transcripts beside [file], in name order; none when the session has no subagents folder. Any
     *  other failure to list it is raised, as a failure to read the session's own file is. */
    private fun subagents(file: Path): List<Path> {
        val dir = file.resolveSibling(file.fileName.toString().removeSuffix(TRANSCRIPT_SUFFIX)).resolve(SUBAGENTS)
        return try {
            Files.newDirectoryStream(dir, "*$TRANSCRIPT_SUFFIX").use { entries ->
                entries.filter(Files::isRegularFile).sorted()
            }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }

    /** File I/O and publication are serialized so simultaneous panel reads do not rescan one file. */
    @Synchronized
    fun read(file: Path, ids: Set<String>): SentTexts {
        if (ids.isEmpty()) return SentTexts(file.toString(), emptyMap(), emptySet())
        val key = file.toRealPath()
        val stamp = stamp(key)
        val old = held.remove(key)
        val entry = when {
            old?.stamp == stamp -> old
            else -> refresh(key, stamp, old)
        }
        held[key] = entry
        if (held.size > HELD_FILES) held.remove(held.keys.first())
        val found = entry.visible.filterKeys { it in ids }
        return SentTexts(file.toString(), found, ids - found.keys)
    }

    private fun stamp(file: Path): Stamp {
        val attrs = FileStat(file, "size,lastModifiedTime")
        return Stamp(
            attrs["size"] as Long,
            attrs["lastModifiedTime"] as FileTime,
            attrs.identity,
        )
    }

    private fun refresh(file: Path, stamp: Stamp, old: Held?): Held {
        val appended = old?.takeIf { isAppend(file, stamp, it) }
        val from = appended?.offset ?: 0L
        val texts = HashMap(appended?.texts.orEmpty())
        val prefixTail = appended?.let {
            it.tail.copyOf((it.tail.size - (it.stamp.size - it.offset)).coerceAtLeast(0).toInt())
        } ?: byteArrayOf()
        val scan = Scan(from, collect, texts, prefixTail)
        opener.open(file, from).use { input ->
            val block = ByteArray(SCAN_BYTES)
            var left = stamp.size - from
            while (left > 0) {
                val count = input.read(block, 0, minOf(left, block.size.toLong()).toInt())
                if (count < 0) break
                scan.accept(block, count)
                left -= count
            }
        }
        return Held(stamp, scan.offset, scan.tail, texts, scan.visible())
    }

    private fun isAppend(file: Path, stamp: Stamp, old: Held): Boolean =
        stamp.size > old.stamp.size && stamp.identity == old.stamp.identity && boundaryMatches(file, old)

    private fun boundaryMatches(file: Path, old: Held): Boolean =
        opener.open(file, old.stamp.size - old.tail.size).use { input ->
            input.readNBytes(old.tail.size).contentEquals(old.tail)
        }

    private data class Stamp(val size: Long, val modified: FileTime, val identity: FileIdentity?)

    private data class Held(
        val stamp: Stamp,
        val offset: Long,
        val tail: ByteArray,
        val texts: Map<String, String>,
        val visible: Map<String, String>,
    )

    /** Complete lines advance the cursor. An EOF line is provisional until a later append completes it. */
    private class Scan(
        from: Long,
        private val collect: SentTextCollector,
        private val texts: MutableMap<String, String>,
        previousTail: ByteArray,
    ) {
        var offset: Long = from
            private set
        var tail: ByteArray = previousTail
            private set
        private val line = ByteArrayOutputStream()
        private var length = 0L
        private var oversized = false

        fun accept(block: ByteArray, count: Int) {
            rememberTail(block, count)
            var start = 0
            for (index in 0 until count) {
                if (block[index] == '\n'.code.toByte()) {
                    segment(block, start, index - start)
                    if (!oversized) collect(line.toByteArray(), texts)
                    offset += length + 1
                    line.reset()
                    length = 0
                    oversized = false
                    start = index + 1
                }
            }
            segment(block, start, count - start)
        }

        fun visible(): Map<String, String> = HashMap(texts).also {
            if (length > 0 && !oversized) collect(line.toByteArray(), it)
        }

        private fun segment(block: ByteArray, start: Int, count: Int) {
            length += count
            if (length > SEND_LINE_BYTES) oversized = true
            if (!oversized) line.write(block, start, count)
        }

        private fun rememberTail(block: ByteArray, count: Int) {
            val retained = minOf(tail.size, (GUARD_BYTES - count).coerceAtLeast(0))
            val added = minOf(count, GUARD_BYTES)
            val next = ByteArray(retained + added)
            tail.copyInto(next, 0, tail.size - retained)
            block.copyInto(next, retained, count - added, count)
            tail = next
        }
    }
}
