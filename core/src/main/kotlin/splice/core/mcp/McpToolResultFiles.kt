package splice.core.mcp

import splice.core.util.SecureFile
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A receipt read/modify/write performed while both the JVM mutex and the native-compatible POSIX lock are held. */
internal fun interface McpResultFileAction<T> {
    fun run(): T
}

/** One JVM mutex and one POSIX record lock share transactions with the native front's fcntl lock. */
internal class McpToolResultFiles(
    private val directory: Path,
    private val limits: McpToolResultStore.Limits,
) {
    fun <T> withLock(action: McpResultFileAction<T>): T = McpResultFileLocks.process.withLock {
        if (Files.isSymbolicLink(directory)) throw IOException("raw MCP results directory is a symbolic link")
        val problem = SecureFile.ownerOnlyDirectory(directory)
        if (problem != null) throw IOException("raw MCP results directory is not private: $problem")
        val attribute = SecureFile.CREATE_0600
        val options = setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val opened = if (attribute == null) {
            FileChannel.open(directory.resolve(".lock"), options)
        } else {
            FileChannel.open(directory.resolve(".lock"), options, attribute)
        }
        opened.use { channel ->
            channel.lock().use { action.run() }
        }
    }

    fun path(callbackId: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(callbackId.toByteArray(Charsets.UTF_8))
        return directory.resolve(hash.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun bytes(path: Path): ByteArray? {
        val attributes = try {
            Files.readAttributes(path, "basic:isRegularFile,size", LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        val size = attributes["size"] as? Long ?: Long.MAX_VALUE
        if (attributes["isRegularFile"] != true || size > limits.maxEntryBytes) {
            throw IOException("raw MCP receipt is not a bounded regular file")
        }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(limits.maxEntryBytes.toInt() + 1).also { bytes ->
                if (bytes.size > limits.maxEntryBytes) {
                    throw IOException(
                        "raw MCP receipt grew beyond its complete-entry byte bound",
                    )
                }
            }
        }
    }

    /** Refusals invalidate an older receipt even when a caller's new byte limit is smaller than that file. */
    fun present(path: Path): Boolean = try {
        val attributes = Files.readAttributes(path, "basic:isRegularFile", LinkOption.NOFOLLOW_LINKS)
        if (attributes["isRegularFile"] != true) throw IOException("raw MCP receipt is not a regular file")
        true
    } catch (_: NoSuchFileException) {
        false
    }

    fun publish(path: Path, text: String, atMs: Long) {
        SecureFile.writeAtomic0600(path, text)
        Files.setLastModifiedTime(path, FileTime.fromMillis(atMs))
    }

    fun prune(nowMs: Long) {
        removeAbandonedWrites()
        val entries = Files.list(directory).use { listed ->
            listed.filter {
                it.fileName.toString().endsWith(
                    ".json",
                ) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
            }
                .map { Entry(it, Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toMillis(), Files.size(it)) }
                .toList().sortedWith(compareBy(Entry::atMs, { it.path.fileName.toString() }))
        }
        val kept = entries.filter { entry ->
            val expired = nowMs - entry.atMs >= limits.maxAgeMs
            if (expired) Files.deleteIfExists(entry.path)
            !expired
        }.toMutableList()
        var bytes = kept.sumOf(Entry::bytes)
        while (kept.size > limits.maxItems || bytes > limits.maxTotalBytes) {
            val oldest = kept.removeFirst()
            Files.deleteIfExists(oldest.path)
            bytes -= oldest.bytes
        }
    }

    private data class Entry(val path: Path, val atMs: Long, val bytes: Long)

    private fun removeAbandonedWrites() {
        Files.list(directory).use { listed ->
            listed.filter { path ->
                val name = path.fileName.toString()
                (name.startsWith(".secure") || name.startsWith(".mcp-result-")) && name.endsWith(".tmp")
            }.forEach(Files::delete)
        }
    }
}

private object McpResultFileLocks {
    val process = ReentrantLock()
}

internal object McpToolResultIdentity {
    private val owned = Regex("^toolu_splice_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun ownedId(value: String): Boolean = owned.matches(value)
}
