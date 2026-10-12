// NEW: Oct 12, 2026 — a session with no name of its own is known by what its person first asked: the first line of the
// first real message of theirs in its transcript, cut to a readable length and held by file stamp.
package splice.client.transcript

import splice.core.util.FileIdentity
import splice.core.util.FileStat
import splice.sessions.transcript.KIND_TAKEN_BACK
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: the first message is at the head of the file; the head is read in doubling windows so a transcript whose first
// line is a large paste is still read whole, and no card poll ever walks past the ceiling.
private const val TITLE_FIRST_WINDOW_BYTES = 64 shl 10

// why: the ceiling on that doubling: a first message larger than this is not a heading worth the read, and the
// card simply has no title rather than a poll that walks megabytes.
private const val TITLE_MAX_BYTES = 2 shl 20

// why: a session title is a card heading, not a summary (TranscriptMetadata's own limit is a different, longer text).
private const val SESSION_TITLE_CHARS = 80

// why: only recent sessions are retained, not every transcript the daemon has ever seen.
private const val TITLE_HELD_FILES = 256

/** The title of one transcript. A title found stays found while the file is the same file, because the first message
 *  of a session never changes; a transcript with no message of the person's yet is read again when it grows. */
internal class TranscriptTitle(
    private val opener: TranscriptOpener,
    private val parser: TranscriptLineParser,
    private val redaction: TranscriptRedaction,
) {
    private val held = LinkedHashMap<Path, Held>()

    fun read(file: Path): String? {
        val key = file.toRealPath()
        val stamp = stamp(key)
        val cached = synchronized(held) { held[key] }
        if (cached != null && current(cached, stamp)) return cached.title
        val entry = Held(stamp, scan(key, stamp.size))
        synchronized(held) {
            held.remove(key)
            held[key] = entry
            if (held.size > TITLE_HELD_FILES) held.remove(held.keys.first())
        }
        return entry.title
    }

    /** A found title holds while the file is the same file; a missing one only while the file has not grown. */
    private fun current(held: Held, now: Stamp): Boolean =
        held.stamp == now || (held.title != null && held.stamp.identity == now.identity)

    private fun stamp(file: Path): Stamp {
        val attrs = FileStat(file, "size,lastModifiedTime")
        return Stamp(attrs["size"] as Long, attrs["lastModifiedTime"] as FileTime, attrs.identity)
    }

    private fun scan(file: Path, size: Long): String? {
        var window = TITLE_FIRST_WINDOW_BYTES.toLong()
        while (true) {
            val count = minOf(size, window, TITLE_MAX_BYTES.toLong()).toInt()
            val bytes = opener.open(file, 0).use { it.readNBytes(count) }
            if (bytes.size != count) return null // the snapshot was truncated during the read
            val whole = count.toLong() == size
            titleIn(if (whole) bytes else bytes.copyOf(bytes.lastIndexOf('\n'.code.toByte()) + 1))?.let { return it }
            if (whole || count >= TITLE_MAX_BYTES) return null
            window *= 2
        }
    }

    private fun titleIn(bytes: ByteArray): String? {
        val assembly = PageAssembly(0, Int.MAX_VALUE, redaction)
        bytes.toString(Charsets.UTF_8).lineSequence()
            .forEach { assembly.accept(parser.parse(it.toByteArray(Charsets.UTF_8))) }
        return assembly.finish()
            .firstOrNull { it.role == TranscriptRole.USER && it.source.kind != KIND_TAKEN_BACK && it.text.isNotBlank() }
            ?.let { line(it.text) }
    }

    private fun line(text: String): String {
        val first = text.lineSequence().map { it.trim() }.first { it.isNotEmpty() }
        return if (first.length <= SESSION_TITLE_CHARS) first else first.take(SESSION_TITLE_CHARS - 1).trimEnd() + "…"
    }

    private data class Stamp(val size: Long, val modified: FileTime, val identity: FileIdentity?)
    private data class Held(val stamp: Stamp, val title: String?)
}
