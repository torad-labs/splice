// NEW: AsyncFileIo contract — the pending-task cap (MAX_PENDING_TASKS = 2_048, private to the
// object) must reject submit() once saturated instead of growing the queue without bound. The
// single background worker is blocked on a latch so nothing drains mid-test, so the lane must end
// holding exactly MAX_PENDING_TASKS and reject the rest. The slots held when the test starts are
// READ (pendingCount), not assumed: the lane is process-wide, and a delayed task another test
// scheduled holds a slot until it runs. If the cap check were removed, the lane would end past the
// cap with nothing rejected, and both assertions below would fail.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncFileIoTest {

    @Test
    fun `submit rejects once the pending cap is saturated, then drains clean`() = saturateThenDrain(heldByOthers = 0)

    @Test
    fun `a failed accepted append is not reported as a settled write`() {
        val file = Path.of("perf-append-failed.jsonl")
        assertTrue(AsyncFileIo.submitFor(file) { throw IllegalStateException("synthetic write failure") })
        assertTrue(!AsyncFileIo.awaitFile(file), "a failed append cannot certify read-your-writes")
        assertTrue(AsyncFileIo.submitFor(file) {})
        assertTrue(!AsyncFileIo.awaitFile(file), "a later append cannot erase the missing row")
        val clean = Path.of("perf-append-healthy.jsonl")
        assertTrue(AsyncFileIo.submitFor(clean) {})
        assertTrue(AsyncFileIo.awaitFile(clean), "a different path is unaffected")
    }

    @Test
    fun `a slot another caller's delayed task holds is counted, not assumed free`() =
        saturateThenDrain(heldByOthers = 1)

    @Test
    fun `pending diagnostics count delayed tasks and include only nested paths below the root`(@TempDir root: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tracked = root.resolve("nested/perf.jsonl").toAbsolutePath().normalize()
        val sibling = root.resolveSibling("${root.fileName}-sibling").resolve("perf.jsonl")
        assertTrue(
            AsyncFileIo.submit {
                started.countDown()
                release.await()
            },
        )
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(AsyncFileIo.submitFor(tracked) {})
            assertTrue(AsyncFileIo.submitFor(sibling) {})
            assertTrue(AsyncFileIo.submit(delayMs = 50) {})
            val snapshot = AsyncFileIo.pendingUnder(root)
            assertTrue(snapshot.count >= 4, "all our runnable and delayed slots remain held")
            assertEquals(listOf(tracked), snapshot.paths, "a sibling with a matching string prefix is outside the root")
        } finally {
            release.countDown()
        }
        assertTrue(AsyncFileIo.awaitFile(tracked))
        assertTrue(AsyncFileIo.awaitFile(sibling))
        assertTrue(AsyncFileIo.drain())
        assertEquals(emptyList<Path>(), AsyncFileIo.pendingUnder(root).paths)
    }

    private fun saturateThenDrain(heldByOthers: Int) {
        val maxPendingTasks = 2_048 // splice.core.util.AsyncFileIo.MAX_PENDING_TASKS (private const)
        val margin = 32 // submissions past the cap, to prove rejection isn't a one-off boundary fluke

        repeat(heldByOthers) {
            assertTrue(AsyncFileIo.submit(OTHER_CALLER_DELAY_MS) {}, "the other caller's task must be accepted")
        }
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        // Occupy the single worker thread so nothing drains while the pending count is saturated.
        assertTrue(
            AsyncFileIo.submit {
                workerStarted.countDown()
                releaseWorker.await()
            },
            "the blocking task itself must be accepted",
        )
        val accepted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val ran = AtomicInteger(0)
        val ranPermits = Semaphore(0)
        try {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker never picked up the blocking task")
            holdRepeatedFileWrites()
            // The lane is process-wide: the blocking task holds a slot, and so does any delayed task another
            // test in this JVM scheduled and the blocked worker cannot run yet. CI run 36387318177 counted
            // 2046 for an assumed 2047 on exactly that. So the slots already held are read, never assumed.
            val held = AsyncFileIo.pendingCount()
            assertTrue(held >= 1 + heldByOthers, "the blocking task and the other caller's tasks hold slots ($held)")

            repeat(maxPendingTasks + margin) {
                val ok = AsyncFileIo.submit {
                    ran.incrementAndGet()
                    ranPermits.release()
                }
                if (ok) accepted.incrementAndGet() else rejected.incrementAndGet()
            }

            // The cap is exact: the lane holds maxPendingTasks, never one more and never one fewer. Only the
            // slots free when the loop began were ours to take; a task another thread submits mid-loop can take
            // one of them, which is why this bounds `accepted` from above instead of pinning it.
            val saturated = AsyncFileIo.pendingCount()
            assertEquals(maxPendingTasks, saturated, "expected the lane to saturate exactly at its cap")
            val free = maxPendingTasks - held
            assertTrue(accepted.get() <= free, "accepted ${accepted.get()} past the $free free slots")
            assertEquals(maxPendingTasks + margin, accepted.get() + rejected.get(), "all submissions counted")
            assertTrue(rejected.get() >= margin, "expected submit() to return false once the pending cap was saturated")
            assertRejectedFileRow()
        } finally {
            // A failed assertion above must not leave the process-wide lane blocked: every later test in
            // this JVM that writes through it would then fail for this test's reason, not its own.
            releaseWorker.countDown()
        }

        // Wait for every accepted task to actually run before calling drain(): drain() itself
        // calls submit() for its completion marker, and while the queue is still draining, pending
        // can transiently sit AT the cap (the just-finished blocking task's own decrement races the
        // marker's increment) — waiting for every run first removes that race instead of masking it.
        // Each task releases one permit, so acquiring `accepted` of them IS every accepted task having
        // run: an event with a deadline, not a poll of real time.
        assertTrue(
            ranPermits.tryAcquire(accepted.get(), 10, TimeUnit.SECONDS),
            "accepted tasks never all ran once the worker was released",
        )
        assertEquals(accepted.get(), ran.get(), "expected every accepted task to run once the worker was released")

        assertTrue(AsyncFileIo.drain(10_000), "drain timed out waiting for the queue to empty")
        assertAcceptedFileRow()
    }

    private fun holdRepeatedFileWrites() {
        val tracked = Path.of("perf-admission-rejection.jsonl")
        assertTrue(AsyncFileIo.submitFor(tracked) {})
        assertTrue(AsyncFileIo.submitFor(tracked) {})
    }

    private fun assertRejectedFileRow() {
        val file = Path.of("perf-admission-rejection.jsonl")
        assertTrue(!AsyncFileIo.submitFor(file) {}, "a saturated lane must refuse the file row")
        assertTrue(!AsyncFileIo.awaitFile(file), "a rejected row must not read as settled")
        assertEquals(
            listOf(file.toAbsolutePath().normalize()),
            AsyncFileIo.pendingUnder(Path.of(".")).paths,
            "a rejected replacement must not hide earlier accepted writes to the same path",
        )
    }

    private fun assertAcceptedFileRow() {
        val lost = Path.of("perf-admission-rejection.jsonl")
        assertTrue(AsyncFileIo.submitFor(lost) {}, "the next file row can be accepted after the lane drains")
        assertTrue(!AsyncFileIo.awaitFile(lost), "the earlier rejected row remains visible as lost")
        val healthy = Path.of("perf-admission-healthy.jsonl")
        assertTrue(AsyncFileIo.submitFor(healthy) {})
        assertTrue(AsyncFileIo.awaitFile(healthy), "an unrelated file still settles")
    }

    private companion object {
        const val OTHER_CALLER_DELAY_MS = 50L
    }
}
