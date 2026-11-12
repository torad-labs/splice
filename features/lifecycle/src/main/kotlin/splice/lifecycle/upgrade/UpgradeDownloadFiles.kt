// NEW (2026-11-10): where a release download's bytes go and how long it waits for them (UpgradeTransfer moves them).
// A release asset never sits in memory: it streams into a part file beside its destination, hashed as it is written, so
// the checksum needs no second read, and the part file lands with one rename only when its sha256 is the published one
// (PartFile). Every other ending removes it. Split from UpgradeTransfer.kt (concentration, 2026-11-10).
package splice.lifecycle.upgrade

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

// why: a TCP connect that has not finished in half a minute is not going to; the stall bound starts once it has.
private const val CONNECT_S = 30L

// why: a minute with no byte is a dead path on any connection that can carry a release at all, and short enough that a
// person watching the command is told within a few minutes rather than left with a frozen line.
private const val DOWNLOAD_STALL_MS = 60_000L

// why: a stall is caught within a second of its bound, and a check a second on one thread costs nothing.
private const val STALL_LOOK_MS = 1_000L

// why: a connection that just dropped often drops the next one sent at once; a short pause lets the path settle.
private const val LEG_PAUSE_MS = 2_000L

// why: three legs in a row with no new byte (three minutes of nothing at the default stall) is a path that is down, not
// slow; a leg that brings bytes resets the count, so a connection that drops every few megabytes still finishes.
private const val FRUITLESS_LEGS = 3

/** The read size of a download and of a local copy. */
internal const val TRANSFER_BUFFER_BYTES = 64 * 1024

/** How a download waits: [connect] bounds the TCP connect, [stallMs] how long a leg may go with no answer and no byte,
 *  [lookMs] how often the watch looks, [pauseMs] the pause before the next leg, and [fruitless] how many legs in a row
 *  may bring nothing before the download is given up. A test steps [clock] by hand. */
internal class DownloadPacing(
    val connect: Duration = Duration.ofSeconds(CONNECT_S),
    val stallMs: Long = DOWNLOAD_STALL_MS,
    val lookMs: Long = STALL_LOOK_MS,
    private val pauseMs: Long = LEG_PAUSE_MS,
    val fruitless: Int = FRUITLESS_LEGS,
    val clock: ElapsedClock = ElapsedClock { MonoClock.nowMs() },
) {
    /** The stall bound in whole seconds, as a sentence says it. */
    val stallSeconds: Long get() = Duration.ofMillis(stallMs).toSeconds()

    fun pause() {
        if (pauseMs > 0) Thread.sleep(pauseMs)
    }
}

/** Where a transfer's bytes go: a [PartFile] for a release asset, memory for the small files a fetch reads whole. */
internal interface TransferSink {
    val size: Long

    fun write(buffer: ByteArray, count: Int)

    /** Drops everything written: the server answered a resume with the whole file. */
    fun restart()
}

internal class MemorySink : TransferSink {
    private val bytes = ByteArrayOutputStream()
    override val size: Long get() = bytes.size().toLong()

    override fun write(buffer: ByteArray, count: Int) = bytes.write(buffer, 0, count)

    override fun restart() = bytes.reset()

    fun bytes(): ByteArray = bytes.toByteArray()
}

/** The file an asset streams into, beside [target] so landing it is one rename in the same directory, hashed as it is
 *  written. Closing it removes whatever did not land, on every path. */
internal class PartFile(private val target: Path) : TransferSink, AutoCloseable {
    val path: Path = target.resolveSibling(".${target.fileName}.part")
    private val digest = MessageDigest.getInstance("SHA-256")
    private var out: OutputStream = open()
    override var size: Long = 0L
        private set

    override fun write(buffer: ByteArray, count: Int) {
        out.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        size += count
    }

    override fun restart() {
        out.close()
        out = open()
        digest.reset()
        size = 0L
    }

    /** Lands the bytes at the target when their sha256 is [expected]; otherwise nothing lands and [close] removes them. */
    fun land(expected: String): Downloaded {
        out.close()
        val actual = HexFormat.of().formatHex(digest.digest())
        if (!expected.equals(actual, ignoreCase = true)) return Downloaded.Mismatch(actual)
        Files.move(path, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return Downloaded.Landed
    }

    override fun close() {
        out.close()
        Files.deleteIfExists(path)
    }

    private fun open(): OutputStream = Files.newOutputStream(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
}
