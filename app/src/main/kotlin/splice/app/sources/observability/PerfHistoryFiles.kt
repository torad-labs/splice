package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal data class PerfHistoryEntry(val path: Path, val fromMs: Long, val resolutionMs: Long, val bytes: Long)

/** Files contain replacements, never a journal of cumulative checkpoints. All paths are constructor-owned. */
internal class PerfHistoryFiles(private val directory: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    val checkpoint: Path = directory.resolve("checkpoint.json")

    fun inventory(
        fromMs: Long = Long.MIN_VALUE,
        untilMs: Long = Long.MAX_VALUE,
        byteLimit: Long = Long.MAX_VALUE,
    ): List<PerfHistoryEntry> = try {
        Files.newDirectoryStream(directory).use { collect(it, fromMs, untilMs, byteLimit) }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    private fun collect(
        paths: DirectoryStream<Path>,
        fromMs: Long,
        untilMs: Long,
        byteLimit: Long,
    ): List<PerfHistoryEntry> {
        val result = ArrayList<PerfHistoryEntry>()
        var owned = 0L
        for (path in paths) {
            val entry = entry(path)?.takeIf { it.fromMs < untilMs && it.fromMs + it.resolutionMs > fromMs } ?: continue
            owned += path.toString().length.toLong() * 2L + INVENTORY_ENTRY_BYTES
            if (owned > byteLimit) throw IOException("query_memory_limit")
            if (result.size >= MAX_FILES) throw IOException("aggregate inventory limit")
            result += entry
        }
        result.sortWith { first, second -> first.fromMs.compareTo(second.fromMs) }
        return result
    }

    private fun entry(path: Path): PerfHistoryEntry? {
        val name = path.fileName.toString()
        val resolution = when {
            name.startsWith("minute-") -> PERF_MINUTE_MS
            name.startsWith("hour-") -> PERF_HOUR_MS
            else -> return null
        }
        val time = name.substringAfter('-').removeSuffix(".json").toLongOrNull() ?: return null
        return PerfHistoryEntry(path, time, resolution, Files.size(path))
    }

    fun read(entry: PerfHistoryEntry): PerfHistoryFile {
        if (entry.bytes > PERF_HISTORY_FILE_BYTES) throw IOException("aggregate file size limit")
        val value = json.decodeFromString<PerfHistoryFile>(boundedText(entry.path, PERF_HISTORY_FILE_BYTES))
        val wrongInterval = value.fromMs != entry.fromMs || value.resolutionMs != entry.resolutionMs
        if (value.schemaVersion != 1 || wrongInterval) {
            throw IOException("aggregate file schema mismatch")
        }
        return value
    }

    fun existing(time: Long, resolution: Long): PerfHistoryFile? {
        val path = path(time, resolution)
        return try {
            read(PerfHistoryEntry(path, time, resolution, Files.size(path)))
        } catch (_: NoSuchFileException) {
            null
        }
    }

    fun write(value: PerfHistoryFile) {
        val owned = value.snapshots.sumOf(PerfHistorySize::snapshot) +
            value.revisions.keys.sumOf { it.length.toLong() * 2L + REVISION_BYTES }
        if (owned > PERF_HISTORY_WORK_BYTES / 2L) throw IOException("aggregate checkpoint memory limit")
        val encoded = json.encodeToString(value)
        if (encoded.length * HISTORY_UTF8_BYTES_PER_CHAR > PERF_HISTORY_FILE_BYTES) {
            throw IOException(
                "aggregate checkpoint size limit",
            )
        }
        SecureFile.writeAtomic0600(path(value.fromMs, value.resolutionMs), encoded)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    fun readCheckpoint(): PerfHistoryCheckpoint? = try {
        if (Files.size(checkpoint) > MAX_CHECKPOINT_BYTES) throw IOException("aggregate checkpoint metadata size limit")
        json.decodeFromString<PerfHistoryCheckpoint>(boundedText(checkpoint, MAX_CHECKPOINT_BYTES))
    } catch (_: NoSuchFileException) {
        null
    }

    fun checkpoint(value: PerfHistoryCheckpoint) {
        val owned = PerfHistorySize.identity(value.identity) + value.issues.sumOf(PerfHistorySize::issue)
        if (owned > MAX_CHECKPOINT_BYTES) throw IOException("aggregate checkpoint metadata size limit")
        val encoded = json.encodeToString(value)
        if (encoded.length * HISTORY_UTF8_BYTES_PER_CHAR > MAX_CHECKPOINT_BYTES) {
            throw IOException(
                "aggregate checkpoint metadata size limit",
            )
        }
        SecureFile.writeAtomic0600(checkpoint, encoded)
        // Atomic replacement alone does not make the directory entry durable after host loss.
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    fun lock(): FileChannel {
        val reason = SecureFile.ownerOnlyDirectory(directory)
        if (reason != null) throw IOException("aggregate directory permissions unavailable")
        val channel = FileChannel.open(
            directory.resolve("history.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val held = try {
            channel.tryLock() != null
        } catch (_: OverlappingFileLockException) {
            false
        }
        if (!held) {
            channel.close()
            throw IOException("aggregate store busy")
        }
        return channel
    }

    fun remove(entry: PerfHistoryEntry) { Files.deleteIfExists(entry.path) }

    fun sealedHour(minute: Long): Boolean = Files.exists(path(PerfHistoryFormat.hour(minute), PERF_HOUR_MS))

    private fun path(time: Long, resolution: Long): Path =
        directory.resolve("${if (resolution == PERF_MINUTE_MS) "minute" else "hour"}-$time.json")

    private fun boundedText(path: Path, limit: Long): String {
        val bytes = Files.newInputStream(path).use { it.readNBytes(limit.toInt() + 1) }
        if (bytes.size > limit) throw IOException("aggregate file size limit")
        return bytes.toString(Charsets.UTF_8)
    }
}

// Bound aggregate inventory to 4,096 entries so damaged retention cannot allocate an unbounded path list.
private const val MAX_FILES = 4_096

// Cap checkpoint metadata at 64 KiB before decoding and atomic publication.
private const val MAX_CHECKPOINT_BYTES = 64L * 1_024

// A UTF-16 code unit needs at most three UTF-8 bytes; surrogate pairs need four bytes for two units.
private const val HISTORY_UTF8_BYTES_PER_CHAR = 3L

// Charge 128 bytes per retained revision entry beyond its key text during history publication.
private const val REVISION_BYTES = 128L

// Charge 256 bytes per inventory descriptor beyond its path text while collecting aggregate files.
private const val INVENTORY_ENTRY_BYTES = 256L
