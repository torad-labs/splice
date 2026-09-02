import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonlSink
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// DR-186's backstop, and this file needs it more than most: the defect DR-178 repairs is an
// UNBOUNDED lock wait, so a regression does not fail these arms, it wedges the suite on them.
private const val HANG_BACKSTOP_S = 60L

// Far above the 250ms budget and far below anything a human would call parked. The point is not to
// measure the budget — unrotatedAppends() proves the degrade actually happened — it is to fail a
// blocking acquisition as a NUMBER rather than leaving it to the backstop.
private const val PARK_CEILING_MS = 5_000L

class JsonlSinkTest {
    @Test
    fun `append rotates one bounded generation`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        JsonlSink.appendLine(file, "1234567890", maxBytes = 22)
        JsonlSink.appendLine(file, "abcdefghij", maxBytes = 22)
        JsonlSink.appendLine(file, "new", maxBytes = 22)

        val rolled = file.resolveSibling("perf.jsonl.1")
        assertTrue(Files.exists(rolled))
        assertEquals(listOf("1234567890", "abcdefghij"), Files.readAllLines(rolled))
        assertEquals(listOf("new"), Files.readAllLines(file))
    }

    // DR-178: the peer is held from a SECOND channel in this same JVM, which is not a weaker
    // stand-in for a second process — java.nio hands the lock to the whole JVM, so the second
    // acquirer gets OverlappingFileLockException where a foreign process would simply block. Both
    // arrive at the same place through acquireBounded, which treats held-is-held either way, and
    // this shape needs no second process to be deterministic.
    @Test
    @Timeout(HANG_BACKSTOP_S)
    fun `a peer holding the lock cannot park the append lane - DR-178`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        JsonlSink.appendLine(file, "1234567890", maxBytes = 15)
        val before = JsonlSink.unrotatedAppends()

        val lockPath = file.resolveSibling("perf.jsonl.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { peer ->
            val held = peer.lock()
            try {
                val startedAt = System.currentTimeMillis()
                JsonlSink.appendLine(file, "abcdefghij", maxBytes = 15)
                val elapsed = System.currentTimeMillis() - startedAt
                assertTrue(elapsed < PARK_CEILING_MS, "the single file-IO lane was parked ${elapsed}ms behind a peer")
            } finally {
                held.release()
            }
        }

        assertEquals(before + 1, JsonlSink.unrotatedAppends(), "a degraded append must be counted, not silent")
        assertEquals(
            listOf("1234567890", "abcdefghij"),
            Files.readAllLines(file),
            "the row must still land: bounded-and-unrotated beats a parked lane, but never beats losing the write",
        )
        // 11 + 11 bytes is over maxBytes, so an append that got the lock WOULD have rotated here.
        // Not rotating is the degrade itself, and it is what makes the counter above meaningful.
        assertFalse(
            Files.exists(file.resolveSibling("perf.jsonl.1")),
            "the rotate is the stat-then-rename that needs the lock; a contended append must skip it, not force it",
        )
    }

    // The other half, without which the arm above is satisfied by an append that NEVER rotates and
    // NEVER acquires: with no peer, the same sizes must rotate and the degrade counter must not move.
    @Test
    @Timeout(HANG_BACKSTOP_S)
    fun `an uncontended append still rotates and records no degrade - DR-178 trap control`(@TempDir tmp: Path) {
        val before = JsonlSink.unrotatedAppends()
        val file = tmp.resolve("perf.jsonl")
        JsonlSink.appendLine(file, "1234567890", maxBytes = 15)
        JsonlSink.appendLine(file, "abcdefghij", maxBytes = 15)

        assertTrue(
            Files.exists(file.resolveSibling("perf.jsonl.1")),
            "an uncontended append must still rotate — the bound is for contention, not a rotation opt-out",
        )
        assertEquals(listOf("abcdefghij"), Files.readAllLines(file))
        assertEquals(before, JsonlSink.unrotatedAppends(), "no peer, no degrade")
    }
}
