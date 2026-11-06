package splice.app.sources.observability.cursor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.app.sources.observability.PerfEvidenceSeen
import splice.core.util.Cancellables
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.SecureFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
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
                total += PerfCursorPruning.prune(path, now, if (path == directory.toAbsolutePath()) reserveFiles else 0)
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

    fun discard(id: String) {
        Files.deleteIfExists(directory.resolve("$id.idx"))
        Files.deleteIfExists(directory.resolve("$id.json"))
    }
}

/** Process-owned bounded cache; each prune also holds the root's cross-process cursor lock. */
internal object PerfCursorPruning {
    private val pending = LinkedHashMap<Path, CursorPruneSweep>()

    @Synchronized
    fun prune(directory: Path, now: Long, reserveFiles: Int): Long {
        expire()
        val key = directory.toAbsolutePath().normalize()
        val sweep = pending.remove(key) ?: open(key) ?: return 0L
        val continued = sweep.retained
        var kept = false
        return Cancellables.withCleanup({ if (!kept) sweep.close() }) {
            val read = sweep.read(now)
            if (read.more) {
                retain(key, sweep)
                kept = true
                throw PerfReadRefusal(PerfReadIssue.CURSOR_INVENTORY_LIMIT)
            }
            // A continued sweep is cleanup only: another owner could have changed an earlier prefix.
            if (continued || read.files > MAX_CURSOR_FILES - reserveFiles) {
                throw PerfReadRefusal(PerfReadIssue.CURSOR_INVENTORY_LIMIT)
            }
            read.bytes
        }
    }

    private fun open(directory: Path): CursorPruneSweep? = try {
        CursorPruneSweep(Files.newDirectoryStream(directory))
    } catch (_: NoSuchFileException) {
        null
    }

    private fun retain(key: Path, sweep: CursorPruneSweep) {
        if (pending.size >= MAX_PENDING_SWEEPS) {
            val oldest = pending.keys.first()
            pending.remove(oldest)?.close()
        }
        sweep.retain()
        pending[key] = sweep
    }

    private fun expire() {
        val entries = pending.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (MonoClock.nowMs() - entry.value.touchedAt > PERF_CURSOR_TTL_MS) {
                entries.remove()
                entry.value.close()
            }
        }
    }

    /** Release a disposed root's optional cleanup state after its users stop, without touching other roots. */
    @Synchronized
    fun closeRoot(root: Path) {
        val directory = root.toAbsolutePath().normalize()
        val entries = pending.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.parent == directory) {
                entries.remove()
                entry.value.close()
            }
        }
    }
}

private class CursorPruneSweep(private val stream: DirectoryStream<Path>) : AutoCloseable {
    private val iterator = stream.iterator()
    private var lease: PerfWorkBudget.Lease? = null
    val retained: Boolean get() = lease != null
    var touchedAt = MonoClock.nowMs()
        private set

    fun retain() {
        if (lease == null) {
            lease = PerfWorkBudget.acquire(PRUNE_SWEEP_BYTES)
                ?: throw PerfReadRefusal(PerfReadIssue.CURSOR_INVENTORY_LIMIT)
        }
        touchedAt = MonoClock.nowMs()
    }

    fun read(now: Long): CursorPruneRead = try {
        var inspected = 0
        var files = 0
        var bytes = 0L
        while (inspected < MAX_CURSOR_FILES && iterator.hasNext()) {
            val path = iterator.next()
            inspected++
            retainedBytes(path, now)?.let { bytes += it; files++ }
        }
        CursorPruneRead(files, bytes, iterator.hasNext())
    } catch (failure: DirectoryIteratorException) {
        throw failure.cause
    }

    private fun retainedBytes(path: Path, now: Long): Long? = try {
        if (Files.getLastModifiedTime(path).toMillis() < now - PERF_CURSOR_TTL_MS) {
            Files.deleteIfExists(path)
            null
        } else {
            Files.size(path)
        }
    } catch (_: NoSuchFileException) {
        // Another cooperating process may have expired this entry between bounded passes.
        null
    }

    override fun close() {
        Cancellables.withCleanup({ lease?.close() }) { stream.close() }
    }
}

private data class CursorPruneRead(val files: Int, val bytes: Long, val more: Boolean)

// Reject cursor manifests above 256 KiB before reading and decoding their segment metadata.
private const val MAX_MANIFEST_BYTES = 256L * 1_024

// Inspect at most 128 files per cursor directory so corrupted inventories cannot grow an unbounded list.
private const val MAX_CURSOR_FILES = 128

// Bound pruning to 256 cursor directories per pass and report inventory overflow explicitly.
private const val MAX_CURSOR_DIRECTORIES = 256

// At most four paused directory streams retain 64 KiB each inside the unchanged shared work allowance.
private const val MAX_PENDING_SWEEPS = 4
private const val PRUNE_SWEEP_BYTES = 64L * 1_024
