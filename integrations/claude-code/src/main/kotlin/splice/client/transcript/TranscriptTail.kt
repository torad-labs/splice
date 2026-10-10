// NEW: V4-444 — bounded positioned tail reads and immutable per-file activity snapshots.
package splice.client.transcript

import splice.core.util.FileIdentity
import splice.core.util.FileStat
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: per-card polls must never walk a multi-megabyte transcript prefix.
private const val TAIL_BLOCK_BYTES = 64 shl 10

// why: a model is looked for past the last message only this far; a transcript whose recent messages name none is
// answered without one rather than read to the ceiling on every cold poll.
private const val TAIL_MODEL_BYTES = 256 shl 10

// why: screenshots can occupy several MiB in one result line; the tail must include that whole record.
private const val TAIL_MAX_BYTES = 16 shl 20

// why: only recent sessions are retained, not every transcript the daemon has ever seen.
private const val TAIL_HELD_FILES = 128

/** What a tail read found: the newest activity message, and the model of the newest assistant message in the window. */
internal data class TailReading(
    val message: TranscriptMessage?,
    val model: String?,
    val answered: TranscriptMessage? = message,
)

/** Cache publication is synchronized; every filesystem operation runs outside that monitor. */
internal class TranscriptTail(
    private val opener: TranscriptOpener,
    private val assembly: TranscriptTailAssembly,
) {
    private val held = LinkedHashMap<Path, Held>()

    fun read(file: Path): TailReading {
        val key = file.toRealPath()
        val stamp = stamp(key)
        val cached = synchronized(held) { held[key]?.takeIf { it.stamp == stamp } }
        if (cached != null) return cached.reading
        val entry = Held(stamp, scan(key, stamp.size))
        synchronized(held) {
            held.remove(key)
            held[key] = entry
            if (held.size > TAIL_HELD_FILES) held.remove(held.keys.first())
        }
        return entry.reading
    }

    private fun stamp(file: Path): Stamp {
        val attrs = FileStat(file, "size,lastModifiedTime")
        return Stamp(attrs["size"] as Long, attrs["lastModifiedTime"] as FileTime, attrs.identity)
    }

    /** Windows grow backwards, with no overlapping reads. A reply whose start lies outside the
     *  ceiling is unavailable rather than an invented partial summary. */
    private fun scan(file: Path, size: Long): TailReading {
        var offset = size
        var read = 0
        val chunks = ArrayDeque<ByteArray>()
        var nextAssembly = TAIL_BLOCK_BYTES
        var last: TailSelection? = null
        while (offset > 0 && read < TAIL_MAX_BYTES) {
            val count = minOf(offset, TAIL_BLOCK_BYTES.toLong(), (TAIL_MAX_BYTES - read).toLong()).toInt()
            offset -= count
            val prefix = opener.open(file, offset).use { it.readNBytes(count) }
            if (prefix.size != count) return TailReading(null, null) // the snapshot was truncated during the read
            chunks.addFirst(prefix)
            read += count
            if (offset == 0L || read >= nextAssembly) {
                val selected = assembly.select(join(chunks, read), offset == 0L)
                // A window that settles the last message may not reach an assistant message yet: read on for its model.
                if (final(selected, offset, read)) return reading(selected)
                last = selected
                nextAssembly *= 2
            }
        }
        // A user message is one whole line, so it stays visible even when nothing earlier fits under the ceiling;
        // never return a partial assistant reply.
        return unsettled(last)
    }

    /** A settled last message is final once an assistant message named its model, nothing earlier is left, or the
     *  search for a model has read [TAIL_MODEL_BYTES]. */
    private fun final(selected: TailSelection, offset: Long, read: Int): Boolean =
        selected.complete && (selected.model != null || offset == 0L || read >= TAIL_MODEL_BYTES)

    private fun reading(selected: TailSelection) =
        TailReading(selected.message, selected.model, selected.answered)

    private fun unsettled(last: TailSelection?): TailReading {
        val settled = last?.takeIf { it.complete }
        return settled?.let(::reading)
            ?: TailReading(last?.message?.takeIf { it.role == TranscriptRole.USER }, last?.model)
    }

    private fun join(chunks: ArrayDeque<ByteArray>, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        return bytes
    }

    private data class Stamp(val size: Long, val modified: FileTime, val identity: FileIdentity?)
    private data class Held(val stamp: Stamp, val reading: TailReading)
}
