// NEW: a folder's cleanup waits for the writes under that folder and for no other write on the shared lane.
package splice.head

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeadFileWriteCleanupTest {
    @Test
    fun `cleanup of a folder returns while the lane holds writes that belong to no file of that folder`(
        @TempDir root: Path,
        @TempDir elsewhere: Path,
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(
                AsyncFileIo.submit {
                    started.countDown()
                    release.await(HELD_SECONDS, TimeUnit.SECONDS)
                },
            )
            assertTrue(started.await(HELD_SECONDS, TimeUnit.SECONDS))
            assertTrue(AsyncFileIo.submit(delayMs = DELAY_MS) {})
            assertTrue(AsyncFileIo.submit(owner = elsewhere) {})
            assertTrue(AsyncFileIo.submitFor(elsewhere.resolve("perf.jsonl")) {})
            val begun = System.nanoTime()
            HeadFileWriteCleanup().awaitWrites(root)
            assertTrue(System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(1), "nothing of this folder's was pending")
        } finally {
            release.countDown()
            assertTrue(AsyncFileIo.drain(HELD_SECONDS * 1_000))
        }
    }

    @Test
    fun `cleanup of a folder waits for a delayed write that names a place under it`(@TempDir root: Path) {
        assertTrue(AsyncFileIo.submit(delayMs = DELAY_MS, owner = root.resolve("store")) {})
        HeadFileWriteCleanup().awaitWrites(root)
        assertTrue(AsyncFileIo.pendingUnder(root).count == 0, "the delayed write ran before the folder could go")
    }

    private companion object {
        const val HELD_SECONDS = 10L
        const val DELAY_MS = 200L
    }
}
