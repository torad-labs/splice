package splice.app.sources.observability.cursor

import splice.core.perf.PerfArchiveName
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

    /**
     * The generations that can hold a row from [sinceMs] on, oldest first.
     *
     * An archived generation's name carries the second it was retired, and the rolled one was last written before
     * the rotation that rolled it: each is an upper bound on every row inside, so a generation that ends a full
     * second before [sinceMs] stays unopened, by the rule the Requests list reads by (PerfArchiveName.endsBefore).
     * Until Nov 11, 2026 a cursor read every generation from its first byte. On a desk with 35 archived generations
     * of 64 MiB its 512 MiB of work ran out inside the third, and every period, the last minute included, answered
     * cursor_work_limit with no rows.
     */
    fun candidates(sinceMs: Long): List<Path> {
        val candidates = archived(sinceMs)
        candidates.sortBy { names.rotatedAt(it.fileName.toString()) }
        val rolled = rolled()
        for (path in listOf(rolled, live)) {
            try {
                val attributes = attributes(path)
                val past = path == rolled && names.endsBefore(attributes.modified, sinceMs)
                if (attributes.isRegularFile && !past) candidates.add(path)
            } catch (_: NoSuchFileException) {
                // An absent live generation carries no records.
            }
        }
        return candidates
    }

    /** Whether [path] is the rolled generation, the one a copied archive can repeat. */
    fun isRolled(path: Path): Boolean = path == rolled()

    private fun rolled(): Path = live.resolveSibling("${live.fileName}.1")

    private fun archived(sinceMs: Long): ArrayList<Path> {
        val root = archive ?: return ArrayList()
        return try {
            val paths = Files.newDirectoryStream(root).use { paths ->
                paths.asSequence().filter { opens(it, sinceMs) }.take(PERF_CURSOR_MAX_SEGMENTS + 1).toList()
            }
            if (paths.size > PERF_CURSOR_MAX_SEGMENTS) throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_LIMIT)
            ArrayList(paths)
        } catch (_: NoSuchFileException) {
            ArrayList()
        }
    }

    private fun opens(archived: Path, sinceMs: Long): Boolean {
        val rotatedAt = names.rotatedAt(archived.fileName.toString()) ?: return false
        return !names.endsBefore(rotatedAt, sinceMs)
    }

    fun capture(path: Path, work: PerfCursorWork): PerfCursorSegment {
        val outline = outline(path)
        return outline.copy(digest = digest(path, outline.end, work))
    }

    /** The generation at [path] as it stands, unread: its digest is empty until a reader takes it from the bytes. */
    fun outline(path: Path): PerfCursorSegment {
        val attributes = attributes(path)
        if (!attributes.isRegularFile) throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_NOT_REGULAR)
        val key = attributes.fileKey
        val id = hash("${key ?: path.toAbsolutePath()}@${attributes.created}")
        return PerfCursorSegment(id, path.fileName.toString(), key, attributes.size, "")
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
        isRolled(path) && previous.any { it.end == segment.end && it.digest == segment.digest }

    fun digest(path: Path, end: Long, work: PerfCursorWork): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() < end) throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_SHORTENED)
            val buffer = ByteBuffer.allocate(DIGEST_BUFFER)
            var at = 0L
            while (at < end) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), end - at).toInt())
                val size = channel.read(buffer, at)
                if (size <= 0) throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_SHORTENED)
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
        val modified = requireNotNull(values["lastModifiedTime"] as? FileTime) { ATTRIBUTES_MISSING }
        return PerfCursorAttributes(
            values["fileKey"]?.toString(),
            size,
            regular,
            created.toMillis(),
            modified.toMillis(),
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
    val modified: Long,
)
