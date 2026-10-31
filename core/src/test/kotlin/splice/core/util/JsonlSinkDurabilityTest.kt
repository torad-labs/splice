// NEW: the WRITER half of V4-45. JsonlSink.append stopped being `Files.write(..., APPEND)` and
// became FileChannel + a full-write loop + force(true); JsonlSinkTest pins the behaviours that
// PREDATE that change, and nothing pinned the change itself or proved the old behaviours survived
// it. This file is that proof, and it is deliberately honest about its own ceiling.
//
// WHAT IS NOT COVERED BY A TEST, AND WHY — stated here rather than left to be inferred:
//
//  1. THE DURABILITY ITSELF. The defect V4-45 measured is a HOST crash, freeze or power loss under
//     ext4 delayed allocation: the file LENGTH is committed before the data blocks are, so the tail
//     reads back as a run of NULs inside otherwise valid JSONL. force(true) is what makes length
//     and bytes land together. A unit test cannot produce that state — it would have to lose the
//     machine — and it must not pretend to: writing NUL bytes into a file by hand reproduces the
//     DAMAGE, never the mechanism, so a test built that way would stay green against a writer with
//     force(true) deleted. Every arm below therefore tests a property that survives the change or
//     is caused by it, and none of them claims to test the fsync.
//
//  2. THE ROTATION'S DIRECTORY ENTRY. JsonlSink.append says it: force(true) covers the file's data
//     and metadata, not the parent directory entry the Files.move creates, so a crash in that
//     window can lose the rename (never the bytes). Same reason as (1) — unreachable in-process.
//
// RECOMMENDATION ON A force(true) MOCK SEAM, asked for and answered rather than silently taken or
// silently skipped: DO NOT ADD ONE. It would mean injecting a channel factory into JsonlSink whose
// only consumer is a test, and what it would buy is an assertion that we CALLED force — an internal
// call count, which is the weakest possible evidence and the exact shape this campaign keeps
// rejecting. It would not move (1) one inch: a mock proves the method was invoked, not that the
// kernel wrote anything back. The call is one line, visible in review, and the honest position is
// that its effect is unobservable from inside this JVM. If that ever needs proving it needs a crash
// harness (a VM snapshot or a device-mapper flakey target), not a mock.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Big enough that one appendLine crosses whatever chunking the platform's write(2) does, small
// enough to stay a unit test. See `a row far larger than one write lands whole`.
private const val BIG_ROW_BYTES = 2 * 1024 * 1024

// A tail window wider than anything any arm here writes, so readTail's mid-file start never drops a
// leading partial line and every assertion below is about the WRITER, not about the window.
private const val WHOLE_FILE_WINDOW = 8 * 1024 * 1024

private const val WRITERS = 2
private const val ROWS_PER_WRITER = 60
private const val RACE_TIMEOUT_S = 30L
private const val HANG_BACKSTOP_S = 60L

class JsonlSinkDurabilityTest {

    /** THE FULL-WRITE LOOP. `channel.write(buffer)` is not obliged to consume the buffer; the old
     *  `Files.write` hid that, the new code loops `while (buffer.hasRemaining())`, and a loop
     *  written as a single `write` call is the regression this arm exists to catch.
     *
     *  HONEST LIMIT, because it changes how this should be read: a short write cannot be FORCED
     *  from here — it is the kernel's choice, and forcing it would need the production seam the
     *  header argues against. So this drives a payload orders of magnitude past a typical single
     *  write and asserts the CONSUMER-OBSERVABLE property the loop exists to guarantee — every byte
     *  present, the row whole, and readTail returning it as ONE complete line. A writer that
     *  dropped the loop fails this arm exactly when the platform short-writes, and passes it
     *  otherwise; that is weaker than a seam would be and it is what is available without one. */
    @Test
    @Timeout(HANG_BACKSTOP_S)
    fun `a row far larger than one write lands whole`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val payload = "x".repeat(BIG_ROW_BYTES)
        val row = """{"ts":1,"blob":"$payload"}"""

        JsonlSink.appendLine(file, row)

        assertEquals(
            (row.length + 1).toLong(),
            Files.size(file),
            "every byte of the row plus its newline must be on disk — a partial write is a lost row",
        )
        val lines = JsonlSink.readTail(file, WHOLE_FILE_WINDOW)
        assertEquals(1, lines.size, "a torn big write would read back as a fragment, or as nothing")
        assertEquals(row, lines[0])
    }

    /** THE TORN-TAIL HEAL, THROUGH THE NEW WRITER AND IN THE V4-45 DAMAGE SHAPE. JsonlSinkTest pins
     *  the 2026-08-25 ENOSPC shape (a truncated JSON fragment with no newline). The shape this row
     *  measured is different — a length-extended NUL run — and it is the one that must not fuse with
     *  the next row, because fusing costs the FOLLOWING row too: one hole would take two turns out
     *  of the operator's spend instead of one. */
    @Test
    fun `a NUL-run tail is healed so the next row lands as its own line`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        // The measured shape, byte for byte: a complete row, then a length-extended run of
        // zeros with NO closing newline. The escape is spelled out rather than embedded so the
        // source file itself stays free of the byte it is about.
        val hole = "\u0000".repeat(300)
        Files.write(file, ("""{"ts":1}""" + "\n" + hole).toByteArray(StandardCharsets.UTF_8))

        JsonlSink.appendLine(file, """{"ts":2}""")

        val lines = JsonlSink.readTail(file, WHOLE_FILE_WINDOW)
        assertEquals(3, lines.size, "the hole costs its own row and NOT the one after it: $lines")
        assertEquals("""{"ts":1}""", lines[0])
        assertEquals(hole, lines[1])
        assertEquals("""{"ts":2}""", lines[2], "the new row must be whole, never fused onto the hole")
    }

    /** ROTATION AT THE BOUNDARY, both sides of it. JsonlSinkTest proves that rotation happens at
     *  all; this pins WHERE, because the comparison is `currentSize + encoded > maxBytes` and an
     *  off-by-one there is invisible to a test that only ever writes far past the cap. The
     *  not-rotating half is the trap control: an arm that only asserts rotation is satisfied by a
     *  writer that rotates on every append. */
    @Test
    fun `rotation happens one byte over the cap and not at it`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val rolled = file.resolveSibling("perf.jsonl.1")
        // Two 10-byte rows plus two newlines is exactly 22 bytes.
        JsonlSink.appendLine(file, "1234567890", maxBytes = 22)
        JsonlSink.appendLine(file, "abcdefghij", maxBytes = 22)

        assertFalse(Files.exists(rolled), "landing exactly ON the cap is not over it")
        assertEquals(listOf("1234567890", "abcdefghij"), JsonlSink.readTail(file, WHOLE_FILE_WINDOW))

        JsonlSink.appendLine(file, "z", maxBytes = 22)

        assertTrue(Files.exists(rolled), "one byte past the cap must roll a generation")
        assertEquals(
            listOf("1234567890", "abcdefghij"),
            JsonlSink.readTail(rolled, WHOLE_FILE_WINDOW),
            "the rotated generation keeps its bytes — a rotation that loses history is worse than an unbounded file",
        )
        assertEquals(listOf("z"), JsonlSink.readTail(file, WHOLE_FILE_WINDOW))
    }

    /** CONCURRENT APPENDS. This path is guarded by a per-path monitor AND a cross-process FileLock,
     *  and the FileChannel rewrite moved the write itself inside a channel this method now opens
     *  per call — so "two writers, every row intact, none fused, none lost" is a property the change
     *  could plausibly have broken and nothing pinned. Asserted on the SET of rows rather than their
     *  order: the interleaving is legitimately nondeterministic, only the integrity is not. */
    @Test
    @Timeout(HANG_BACKSTOP_S)
    fun `two threads appending concurrently lose no row and fuse none`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val start = CountDownLatch(1)
        val done = CountDownLatch(WRITERS)
        val pool = Executors.newFixedThreadPool(WRITERS)
        try {
            repeat(WRITERS) { writer ->
                pool.execute {
                    start.await()
                    repeat(ROWS_PER_WRITER) { i -> JsonlSink.appendLine(file, """{"w":$writer,"i":$i}""") }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(RACE_TIMEOUT_S, TimeUnit.SECONDS), "a concurrent append wedged the writers")
        } finally {
            // shutdown(), not shutdownNow(): the await above already proves both writers finished,
            // and shutdownNow returns the queue it drained — a discarded non-Unit return the
            // repo's -Xreturn-value-checker flags.
            pool.shutdown()
        }

        val expected = (0 until WRITERS).flatMap { w -> (0 until ROWS_PER_WRITER).map { """{"w":$w,"i":$it}""" } }
        val lines = JsonlSink.readTail(file, WHOLE_FILE_WINDOW)
        assertEquals(expected.size, lines.size, "every row must land exactly once")
        assertEquals(expected.toSortedSet(), lines.toSortedSet(), "a fused or truncated row shows up as a wrong line")
    }
}
