// One retained perf generation, its file identity and the proof that a later read still refers to its bytes.
package splice.app.sources

import splice.app.sources.dense.PerfCachedEntry
import splice.app.sources.dense.PerfEntryFactory
import splice.app.sources.dense.PerfGenerationRef
import splice.core.util.JsonlAppendProof
import splice.core.util.JsonlAppendReceipt
import splice.core.util.JsonlFileVersion
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime
import java.util.LinkedList

internal data class PerfReadVersion(
    val path: Path,
    val version: JsonlFileVersion?,
    val receipt: JsonlAppendReceipt?,
    val complete: Long,
    val digest: ByteArray?,
) {
    fun coherent(): Boolean {
        val before = version ?: return false
        return try {
            val after = JsonlAppendProof.version(path)
            when {
                after.changed != null && before == after -> true
                JsonlAppendProof.current(path)?.continues(before, after, receipt) == true -> true
                after.changed == null -> FileChannel.open(path, READ).use { channel ->
                    PerfPrefixDigest().matches(channel, after.size, complete, digest)
                }
                else -> false
            }
        } catch (_: IOException) {
            false
        }
    }
}

internal class PerfFileStamp(path: Path) {
    val version: JsonlFileVersion = JsonlAppendProof.version(path)
    val receipt: JsonlAppendReceipt? = JsonlAppendProof.current(path)
    val regular: Boolean get() = version.regular
    val modified: FileTime get() = version.modified
    val changed: FileTime? get() = version.changed
    fun identity(path: Path): Any = version.key ?: (path.toAbsolutePath().normalize() to version.created)
}

internal class PerfCachedGeneration(path: Path, var priority: Int, entries: PerfEntryFactory) {
    val ref = PerfGenerationRef(path)
    var path: Path
        get() = ref.path
        set(value) {
            ref.path = value
        }
    val lines = entries.of(ref)
    val ranges = LinkedList<PerfSkippedRange>()
    var rangeBytes = 0L
    var changeTime: FileTime? = null
    var complete = 0L
    var size = 0L
    var modified: FileTime? = null
    var trailingCr = false
    var readLimit = 0L
    var prefixDigest: ByteArray? = null
    var prefixState: PerfPrefixState? = null
    var version: JsonlFileVersion? = null
    var receipt: JsonlAppendReceipt? = null
    val metadataBytes: Long
        get() = PerfCacheFootprint.generationBytes(path) + rangeBytes

    fun evicted(entry: PerfCachedEntry) {
        val row = entry.line.row ?: return
        if (entry.line.probe || entry.raw != null) return
        val previous = ranges.lastOrNull { it.end == entry.start }
        val range = previous ?: PerfSkippedRange(entry.start, entry.end, row.ts).also {
            val position = ranges.indexOfFirst { next -> next.start > it.start }
                .takeIf { index -> index >= 0 } ?: ranges.size
            ranges.add(position, it)
            rangeBytes += it.retainedBytes
        }
        val before = range.retainedBytes
        range.add(entry.line, entry.end)
        rangeBytes += range.retainedBytes - before
    }

    fun appendProof(opened: PerfFileStamp): PerfPrefixState? = prefixState?.takeIf {
        version?.let { before -> opened.receipt?.continues(before, opened.version, receipt) } == true
    }

    fun unchanged(nextSize: Long, nextModified: FileTime, changed: FileTime?): Boolean {
        if (changed == null || nextSize != complete) return false
        return !changed(nextSize, nextModified) && changeTime == changed
    }

    fun changed(nextSize: Long, nextModified: FileTime): Boolean = nextSize != size || nextModified != modified

    /** Whether a new file of [size] bytes may be a copy of this generation, taken before a rename left this
     *  generation's name holding another file, as PerfStats.archiveRolled copies `.1` before JsonlSink renames the
     *  live file over it. The copy holds every byte this generation held when it was last read, and any written to it
     *  after, so it is at least [complete] long. This only names a candidate: the reader's prefix proof decides. [held]
     *  is this generation's identity; a name that is gone holds it no more. */
    fun <T : Any> copiedTo(size: Long, held: T): Boolean {
        if (complete <= 0L || complete > size) return false
        return try {
            PerfFileStamp(path).identity(path) != held
        } catch (_: IOException) {
            true
        }
    }
}
