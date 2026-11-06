package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
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
        if (Files.size(path) > MAX_MANIFEST_BYTES) throw IOException("cursor_manifest_limit")
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1) }
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("cursor_manifest_limit")
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    fun save(manifest: PerfCursorManifest) {
        SecureFile.writeAtomic0600(directory.resolve("${manifest.id}.json"), json.encodeToString(manifest))
    }

    fun index(id: String): FileChannel = FileChannel.open(directory.resolve("$id.idx"), StandardOpenOption.READ)

    fun create(id: String): FileChannel {
        val reason = SecureFile.ownerOnlyDirectory(directory)
        if (reason != null) throw IOException("cursor_directory_permissions")
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
                throw IOException("cursor_index_shortened")
            }
        }
        buffer.flip()
        return PerfCursorPosition(buffer.int, buffer.long, buffer.long)
    }

    fun prune(now: Long): Long {
        val root = directory.toAbsolutePath().parent
        if (!Files.exists(root)) return 0L
        return Files.newDirectoryStream(root, "*-cursors").use { directories ->
            var total = 0L
            var count = 0
            for (path in directories) {
                if (++count > MAX_CURSOR_DIRECTORIES) throw IOException("cursor_inventory_limit")
                total += pruneDirectory(path, now)
            }
            total
        }
    }

    fun lock(): FileChannel {
        val reason = SecureFile.ownerOnlyDirectory(directory)
        if (reason != null) throw IOException("cursor_directory_permissions")
        val channel = FileChannel.open(
            directory.parent.resolve("perf-cursors.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val locked = try {
            channel.tryLock() != null
        } catch (_: OverlappingFileLockException) {
            false
        }
        if (!locked) {
            channel.close()
            throw IOException("cursor_busy")
        }
        return channel
    }

    private fun pruneDirectory(directory: Path, now: Long): Long {
        val paths = listDirectory(directory)
        var bytes = 0L
        for (path in paths) {
            if (Files.getLastModifiedTime(path).toMillis() < now - PERF_CURSOR_TTL_MS) {
                Files.deleteIfExists(path)
            } else {
                bytes += Files.size(path)
            }
        }
        return bytes
    }

    private fun listDirectory(directory: Path): List<Path> = try {
        Files.newDirectoryStream(directory).use { stream ->
            val list = stream.asSequence().take(MAX_CURSOR_FILES + 1).toList()
            if (list.size > MAX_CURSOR_FILES) throw IOException("cursor_inventory_limit")
            list
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    fun discard(id: String) {
        Files.deleteIfExists(directory.resolve("$id.idx"))
        Files.deleteIfExists(directory.resolve("$id.json"))
    }
}

private const val MAX_MANIFEST_BYTES = 256L * 1_024
private const val MAX_CURSOR_FILES = 128
private const val MAX_CURSOR_DIRECTORIES = 256
