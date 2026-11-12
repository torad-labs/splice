package splice.app.sources.observability

import splice.core.util.SecureFile
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Read-only inspection is separate from the explicit, durable writable-session transition. */
internal class PerfHistoryWriter(private val directory: Path) : AutoCloseable {
    private val marker = directory.resolve("writer-open.json")
    private var channel: FileChannel? = null

    fun uncertain(): Boolean = Files.exists(marker)

    fun open(epochMs: Long) {
        if (channel != null) return
        if (SecureFile.ownerOnlyDirectory(directory) != null) throw IOException("history directory permissions")
        val opened = FileChannel.open(
            directory.resolve("writer.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        try {
            if (!lock(opened)) throw IOException("history writer already open")
            SecureFile.writeAtomic0600(marker, "{\"opened_epoch_ms\":$epochMs}")
            forceDirectory()
            channel = opened
        } finally {
            if (channel !== opened) opened.close()
        }
    }

    fun clean() {
        Files.deleteIfExists(marker)
        forceDirectory()
    }

    override fun close() {
        channel?.close()
        channel = null
    }

    private fun lock(channel: FileChannel): Boolean = try {
        channel.tryLock() != null
    } catch (_: OverlappingFileLockException) {
        false
    }

    private fun forceDirectory() {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}
