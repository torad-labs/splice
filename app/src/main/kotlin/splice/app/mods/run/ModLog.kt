package splice.app.mods.run

import splice.app.mods.install.ModPrivateDir
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND

private const val LOG_LINES_PER_SECOND = 200 // why: concurrent stderr and $.log floods cannot fill the log lane
private const val LOG_BYTES_PER_SECOND = 256 * 1024 // why: large messages must not evade the line cap
private const val LOG_GENERATION_BYTES = 8L * 1024 * 1024 // why: current and one older copy cap disk at 16 MiB

internal class ModLog(
    private val file: Path,
    private val drops: ModDrops,
    private val clock: WallClock,
    private val daemonLog: LogSink,
) {
    private var second = Long.MIN_VALUE
    private var lines = 0
    private var bytes = 0
    private var size: Long? = null
    private var reportedDrops = 0L
    private var reportedAt = Long.MIN_VALUE
    private var unavailable = false

    @Synchronized
    fun write(message: String) {
        val now = clock() / 1000
        val payload = (message.replace('\r', ' ').replace('\n', ' ') + "\n").encodeToByteArray()
        if (second != now) {
            reportDrops()
            second = now
            lines = 0
            bytes = 0
        }
        if (lines == LOG_LINES_PER_SECOND || payload.size > LOG_BYTES_PER_SECOND - bytes) {
            drops.log()
            return
        }
        lines++
        bytes += payload.size
        keepingLog { append(payload) }
    }

    @Synchronized
    fun reportDrops(flush: Boolean = false) {
        val now = clock() / 1000
        if (!flush && reportedAt == now) return
        reportedAt = now
        val eventDrops = drops.byEvent().values.sum() + drops.transportCount()
        val logDrops = drops.logCount()
        val total = eventDrops + logDrops
        if (total == reportedDrops) return
        reportedDrops = total
        val line = "Dropped $eventDrops events or pipe lines and $logDrops log lines.\n"
        keepingLog { append(line.encodeToByteArray()) }
    }

    private inline fun keepingLog(block: () -> Unit) {
        Cancellables.runCatchingBestEffort(block).onFailure {
            drops.log()
            if (!unavailable) {
                daemonLog("[mods] cannot write ${file.fileName}: ${SafeFailureText.render(it)}\n")
                unavailable = true
            }
        }.onSuccess { unavailable = false }
    }

    private fun append(payload: ByteArray) {
        val used = size ?: openSize()
        if (used + payload.size > LOG_GENERATION_BYTES) {
            Files.move(file, file.resolveSibling("${file.fileName}.1"), REPLACE_EXISTING)
            size = null
        }
        if (size == null) size = openSize()
        Files.write(file, payload, APPEND, NOFOLLOW_LINKS)
        size = checkNotNull(size) + payload.size
    }

    private fun openSize(): Long {
        ModPrivateDir.create(file.parent)
        if (!Files.exists(file, NOFOLLOW_LINKS)) SecureFile.createNew0600(file, byteArrayOf())
        // An old log may have been created before the caps or owner-only permissions were in force.
        return Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            .also { require(it.isRegularFile) { "the mod log is not a regular file" } }.size()
            .also { size = it }
    }
}
