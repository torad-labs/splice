package splice.app.sources.observability.cursor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.app.sources.observability.PerfEvidenceSeen
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class PerfCursorFiles(private val directory: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(id: String): PerfCursorManifest {
        val path = directory.resolve("$id.json")
        if (Files.size(path) > MAX_MANIFEST_BYTES) throw PerfReadRefusal(PerfReadIssue.CURSOR_MANIFEST_LIMIT)
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1) }
        if (bytes.size > MAX_MANIFEST_BYTES) throw PerfReadRefusal(PerfReadIssue.CURSOR_MANIFEST_LIMIT)
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    fun save(manifest: PerfCursorManifest) {
        SecureFile.writeAtomic0600(directory.resolve("${manifest.id}.json"), json.encodeToString(manifest))
    }

    fun index(id: String): FileChannel = FileChannel.open(directory.resolve("$id.idx"), StandardOpenOption.READ)

    fun evidenceIds(id: String): PerfEvidenceSeen = PerfEvidenceSeen(directory.resolve("$id.seen"))

    fun create(id: String): FileChannel {
        val reason = SecureFile.ownerOnlyDirectory(directory)
        if (reason != null) throw PerfReadRefusal(PerfReadIssue.CURSOR_DIRECTORY_PERMISSIONS)
        return FileChannel.open(directory.resolve("$id.idx"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    fun position(channel: FileChannel, ordinal: Long): PerfCursorPosition {
        val buffer = ByteBuffer.allocate(PERF_CURSOR_ENTRY_BYTES)
        while (buffer.hasRemaining()) {
            if (channel.read(
                    buffer,
                    ordinal * PERF_CURSOR_ENTRY_BYTES + buffer.position(),
                ) <= 0
            ) {
                throw PerfReadRefusal(PerfReadIssue.CURSOR_INDEX_SHORTENED)
            }
        }
        buffer.flip()
        return PerfCursorPosition(buffer.int, buffer.long, buffer.long)
    }

    /** The caller holds [lock] through admission and creation, including across source instances/processes. */
    fun prune(now: Long, reserveFiles: Int): Long {
        val root = directory.toAbsolutePath().parent
        if (!Files.exists(root)) return 0L
        return Files.newDirectoryStream(root, "*-cursors").use { directories ->
            var total = 0L
            var count = 0
            for (path in directories) {
                if (++count > MAX_CURSOR_DIRECTORIES) throw PerfReadRefusal(PerfReadIssue.CURSOR_INVENTORY_LIMIT)
                total += pruneDirectory(path, now, if (path == directory.toAbsolutePath()) reserveFiles else 0)
            }
            total
        }
    }

    fun lock(): FileChannel {
        val reason = SecureFile.ownerOnlyDirectory(directory)
        if (reason != null) throw PerfReadRefusal(PerfReadIssue.CURSOR_DIRECTORY_PERMISSIONS)
        val channel = FileChannel.open(
            directory.parent.resolve("perf-cursors.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        var locked = false
        return Cancellables.withCleanup({ if (!locked) channel.close() }) {
            locked = try {
                channel.tryLock() != null
            } catch (_: OverlappingFileLockException) {
                false
            }
            if (!locked) throw PerfReadRefusal(PerfReadIssue.CURSOR_BUSY)
            channel
        }
    }

    private fun pruneDirectory(directory: Path, now: Long, reserveFiles: Int): Long {
        val paths = listDirectory(directory)
        var bytes = 0L
        var retained = 0
        // An oversized old inventory can recover over bounded passes, but never evict an unexpired token.
        for (path in paths.take(MAX_CURSOR_FILES)) {
            if (Files.getLastModifiedTime(path).toMillis() < now - PERF_CURSOR_TTL_MS) {
                Files.deleteIfExists(path)
            } else {
                bytes += Files.size(path)
                retained++
            }
        }
        if (paths.size > MAX_CURSOR_FILES || retained > MAX_CURSOR_FILES - reserveFiles) {
            throw PerfReadRefusal(PerfReadIssue.CURSOR_INVENTORY_LIMIT)
        }
        return bytes
    }

    private fun listDirectory(directory: Path): List<Path> = try {
        Files.newDirectoryStream(directory).use { stream ->
            stream.asSequence().take(MAX_CURSOR_FILES + 1).toList()
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    fun discard(id: String) {
        Files.deleteIfExists(directory.resolve("$id.idx"))
        Files.deleteIfExists(directory.resolve("$id.json"))
    }
}

// Reject cursor manifests above 256 KiB before reading and decoding their segment metadata.
private const val MAX_MANIFEST_BYTES = 256L * 1_024

// Inspect at most 128 files per cursor directory so corrupted inventories cannot grow an unbounded list.
private const val MAX_CURSOR_FILES = 128

// Bound pruning to 256 cursor directories per pass and report inventory overflow explicitly.
private const val MAX_CURSOR_DIRECTORIES = 256
