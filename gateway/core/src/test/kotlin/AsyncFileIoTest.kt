// NEW: AsyncFileIo contract — the pending-task cap (MAX_PENDING_TASKS = 2_048, private to the
// object) must reject submit() once saturated instead of growing the queue without bound. The
// single background worker is blocked on a latch so nothing drains mid-test, making the pending
// counter arithmetic exact: the still-blocking task already holds one slot, so exactly
// MAX_PENDING_TASKS - 1 further submits are accepted before submit() starts returning false. If
// the cap check were removed, every submit would return true and the accepted-count assertion
// below would fail.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.AsyncFileIo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncFileIoTest {

    @Test
    fun `submit rejects once the pending cap is saturated, then drains clean`() {
        val maxPendingTasks = 2_048 // splice.core.util.AsyncFileIo.MAX_PENDING_TASKS (private const)
        val margin = 32 // submissions past the cap, to prove rejection isn't a one-off boundary fluke

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
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker never picked up the blocking task")

        val accepted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val ran = AtomicInteger(0)
        val extraCount = maxPendingTasks + margin
        repeat(extraCount) {
            val ok = AsyncFileIo.submit { ran.incrementAndGet() }
            if (ok) accepted.incrementAndGet() else rejected.incrementAndGet()
        }

        // The still-blocking task already holds one of the maxPendingTasks slots.
        val expectedAccepted = maxPendingTasks - 1
        assertEquals(expectedAccepted, accepted.get(), "expected the pending cap to accept exactly $expectedAccepted")
        assertEquals(
            extraCount - expectedAccepted,
            rejected.get(),
            "expected submit() to start returning false once the pending cap was saturated",
        )

        releaseWorker.countDown()

        // Wait for every accepted task to actually run before calling drain(): drain() itself
        // calls submit() for its completion marker, and while the queue is still draining, pending
        // can transiently sit AT the cap (the just-finished blocking task's own decrement races the
        // marker's increment) — polling ran first removes that race instead of masking it.
        val deadline = System.currentTimeMillis() + 10_000
        while (ran.get() < accepted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(accepted.get(), ran.get(), "expected every accepted task to run once the worker was released")

        assertTrue(AsyncFileIo.drain(10_000), "drain timed out waiting for the queue to empty")
    }
}
