package splice.control.mcp

import splice.core.util.SecureFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Opt-in evidence from real TERM handlers, shared only by the two stubborn lifecycle-test children.
 *
 *  Each child holds an exclusive lock on its own `<slot>.lock` from launch and lets it go only once its TERM receipt
 *  is whole, so a child that took TERM first blocks on its peer's lock and the kernel wakes it the moment the peer has
 *  its TERM too, or has died, which the receipt check then refuses. A directory watch cannot carry this: on macOS the
 *  JDK's watch service polls every two seconds, as long as the host's whole TERM grace, so the first child was killed
 *  before it was told its peer had been signalled with it. */
public object FakeMcpTermReceipts {
    /** WHY: retain the test's three-second bound for real TERM receipt/overlap, not total reaping. */
    public const val TERM_OVERLAP_WAIT_MS: Long = 3_000L

    // WHY: a receipt contains only a PID, process birth timestamp, and optional elapsed milliseconds.
    private const val RECEIPT_BYTES = 128

    /** This child's own lock, held from launch until its TERM receipt is published; null outside the shutdown test. */
    @Volatile
    private var held: FileLock? = null

    /** Reads only the fixture's small, regular, private receipt, never an unbounded child output stream. */
    public fun read(path: Path): List<String> {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "missing TERM receipt: $path" }
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(RECEIPT_BYTES + 1) }
        check(bytes.isNotEmpty() && bytes.size <= RECEIPT_BYTES) { "invalid TERM receipt size: $path" }
        return bytes.toString(Charsets.UTF_8).split('|')
    }

    /** At launch, before the handshake answers: the peer can only look for this lock after both children are up. */
    internal fun hold() {
        val directory = directory() ?: return
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        held = FileChannel.open(directory.resolve("${slot()}.lock"), setOf(CREATE_NEW, WRITE), owner).lock()
    }

    internal fun record() {
        val directory = directory() ?: return
        val started = System.nanoTime()
        val slot = slot()
        val current = ProcessHandle.current()
        val identity = "${current.pid()}|${current.info().startInstant().orElseThrow()}"
        SecureFile.createNew0600(directory.resolve("$slot.term"), identity.toByteArray())
        checkNotNull(held) { "TERM arrived before this child held its lock" }.channel().close()
        val peer = if (slot == "1") "2" else "1"
        // Returns once the peer released its lock after its own receipt, or exited without one.
        FileChannel.open(directory.resolve("$peer.lock"), READ, NOFOLLOW_LINKS).use { channel ->
            val _ = channel.lock(0L, Long.MAX_VALUE, true)
        }
        val receipt = read(directory.resolve("$peer.term"))
        check(receipt.size == 2)
        val handle = ProcessHandle.of(receipt[0].toLong()).orElseThrow()
        check(handle.isAlive && handle.info().startInstant().orElseThrow().toString() == receipt[1])
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        check(elapsed < TERM_OVERLAP_WAIT_MS) { "peer TERM overlap took ${elapsed}ms" }
        SecureFile.createNew0600(
            directory.resolve("$slot.overlap"),
            "${receipt.joinToString("|")}|$elapsed".toByteArray(),
        )
    }

    private fun directory(): Path? = System.getenv("FAKE_TERM_DIRECTORY")?.let(Path::of)?.also { directory ->
        check(directory.isAbsolute && directory.toRealPath() == directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS))
    }

    private fun slot(): String = checkNotNull(System.getenv("FAKE2")).also { check(it == "1" || it == "2") }
}
