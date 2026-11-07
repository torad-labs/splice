package splice.control.mcp

import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit

/** Opt-in evidence from real TERM handlers, shared only by the two stubborn lifecycle-test children. */
public object FakeMcpTermReceipts {
    /** WHY: retain the test's three-second bound for real TERM receipt/overlap, not total reaping. */
    public const val TERM_OVERLAP_WAIT_MS: Long = 3_000L

    // WHY: a receipt contains only a PID, process birth timestamp, and optional elapsed milliseconds.
    private const val RECEIPT_BYTES = 128

    /** Reads only the fixture's small, regular, private receipt, never an unbounded child output stream. */
    public fun read(path: Path): List<String> {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "missing TERM receipt: $path" }
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(RECEIPT_BYTES + 1) }
        check(bytes.isNotEmpty() && bytes.size <= RECEIPT_BYTES) { "invalid TERM receipt size: $path" }
        return bytes.toString(Charsets.UTF_8).split('|')
    }

    internal fun record() {
        val directory = System.getenv("FAKE_TERM_DIRECTORY")?.let(Path::of) ?: return
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(TERM_OVERLAP_WAIT_MS)
        check(directory.isAbsolute && directory.toRealPath() == directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS))
        val slot = System.getenv("FAKE2")
        check(slot == "1" || slot == "2")
        val peer = directory.resolve("${if (slot == "1") "2" else "1"}.term")
        val current = ProcessHandle.current()
        val identity = "${current.pid()}|${current.info().startInstant().orElseThrow()}"
        directory.fileSystem.newWatchService().use { watch ->
            // Register before publishing/checking: an already-created peer receipt cannot be missed.
            directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            SecureFile.createNew0600(directory.resolve("$slot.term"), identity.toByteArray())
            while (!Files.exists(peer, NOFOLLOW_LINKS)) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "peer did not receive TERM within ${TERM_OVERLAP_WAIT_MS}ms" }
                val key = checkNotNull(watch.poll(remaining, TimeUnit.NANOSECONDS)) { "peer TERM receipt timed out" }
                key.pollEvents()
                check(key.reset()) { "TERM receipt directory no longer watched" }
            }
            val receipt = read(peer)
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
    }
}
