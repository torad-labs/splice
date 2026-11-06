package splice.app.sources.observability

import splice.core.perf.PerfArchiveName
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Bounded discovery; an archive copy aliases only the rolled generation it copied. */
internal class PerfCursorInventory(private val live: Path, private val archive: Path?) {
    private val names = PerfArchiveName(live.fileName.toString())

    fun candidates(): List<Path> {
        val candidates = archived()
        candidates.sortBy { names.rotatedAt(it.fileName.toString()) }
        for (path in listOf(live.resolveSibling("${live.fileName}.1"), live)) {
            try {
                if (attributes(path).isRegularFile) candidates.add(path)
            } catch (_: NoSuchFileException) {
                // An absent live generation carries no records.
            }
        }
        return candidates
    }

    private fun archived(): ArrayList<Path> {
        val root = archive ?: return ArrayList()
        return try {
            val paths = Files.newDirectoryStream(root).use { paths ->
                paths.asSequence().filter { names.rotatedAt(it.fileName.toString()) != null }
                    .take(PERF_CURSOR_MAX_SEGMENTS + 1).toList()
            }
            if (paths.size > PERF_CURSOR_MAX_SEGMENTS) throw IOException("cursor_segment_limit")
            ArrayList(paths)
        } catch (_: NoSuchFileException) {
            ArrayList()
        }
    }

    fun capture(path: Path, work: PerfCursorWork): PerfCursorSegment {
        val attributes = attributes(path)
        if (!attributes.isRegularFile) throw IOException("cursor_segment_not_regular")
        val end = attributes.size
        val key = attributes.fileKey
        val id = hash("${key ?: path.toAbsolutePath()}@${attributes.created}")
        return PerfCursorSegment(id, path.fileName.toString(), key, end, digest(path, end, work))
    }

    fun resolve(segment: PerfCursorSegment, candidates: List<Path>, work: PerfCursorWork): Path? {
        val ordered = candidates.sortedBy { attributes(it).fileKey != segment.fileKey }
        return ordered.firstOrNull { path ->
            val attributes = attributes(path)
            attributes.isRegularFile && attributes.size >= segment.end && digest(
                path,
                segment.end,
                work,
            ) == segment.digest
        }
    }

    fun aliasesRolled(path: Path, segment: PerfCursorSegment, previous: List<PerfCursorSegment>): Boolean =
        path.fileName.toString() == "${live.fileName}.1" && previous.any {
            it.end == segment.end && it.digest == segment.digest
        }

    fun digest(path: Path, end: Long, work: PerfCursorWork): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() < end) throw IOException("cursor_segment_shortened")
            val buffer = ByteBuffer.allocate(DIGEST_BUFFER)
            var at = 0L
            while (at < end) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), end - at).toInt())
                val size = channel.read(buffer, at)
                if (size <= 0) throw IOException("cursor_segment_shortened")
                work.charge(size.toLong())
                digest.update(buffer.array(), 0, size)
                at += size
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun attributes(path: Path): PerfCursorAttributes {
        val values = Files.readAttributes(path, "basic:*", LinkOption.NOFOLLOW_LINKS)
        val size = requireNotNull(values["size"] as? Long) { ATTRIBUTES_MISSING }
        val regular = requireNotNull(values["isRegularFile"] as? Boolean) { ATTRIBUTES_MISSING }
        val created = requireNotNull(values["creationTime"] as? FileTime) { ATTRIBUTES_MISSING }
        return PerfCursorAttributes(
            values["fileKey"]?.toString(),
            size,
            regular,
            created.toMillis(),
        )
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

// Hash frozen file prefixes through a reusable 64 KiB buffer instead of loading entire segments.
private const val DIGEST_BUFFER = 64 * 1_024
private const val ATTRIBUTES_MISSING = "cursor_attributes_missing"

private data class PerfCursorAttributes(
    val fileKey: String?,
    val size: Long,
    val isRegularFile: Boolean,
    val created: Long,
)
