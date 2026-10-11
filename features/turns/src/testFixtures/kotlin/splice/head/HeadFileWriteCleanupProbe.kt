// NEW: an explicitly launched JUnit child proves module-default cleanup observes a live file writer.
package splice.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import splice.core.util.AsyncFileIo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// why: leave each bounded five-second cleanup attempt time to finish before the parent reaps its writer.
private const val PROBE_RELEASE_TIMEOUT_SECONDS = 30L

// why: remain delayed through the five-second cleanup refusal, then run within the reaper's bound.
private const val UNTRACKED_DELAY_MS = 7_000L
private const val PROBE_SENTINEL = "cleanup-sentinel.txt"

/** Real JUnit children inherit module configuration, rather than naming a cleanup strategy. */
public object HeadFileWriteCleanupProbe {
    private var release = CountDownLatch(1)
    private var delayedStarted = CountDownLatch(1)
    private var writerFinished = CountDownLatch(1)
    private val roots = mutableListOf<Path>()

    /** Run each case alone: a blocked tracked task must not hide an omitted delayed-slot check. */
    public fun verifyConfiguration() {
        verifyChild(BlockedWrite::class.java, "late-head-perf.jsonl")
        verifyChild(DelayedWrite::class.java, null)
    }

    private fun verifyChild(child: Class<*>, trackedFile: String?) {
        release = CountDownLatch(1)
        delayedStarted = CountDownLatch(1)
        writerFinished = CountDownLatch(1)
        roots.clear()
        val listener = SummaryGeneratingListener()
        val request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(child)).build()
        try {
            LauncherFactory.create().execute(request, listener)
            val summary = listener.summary
            assertEquals(1L, summary.testsStartedCount)
            assertEquals(1L, summary.testsFailedCount, "default cleanup must not delete past the live writer")
            val failure = summary.failures.single().exception
            val messages = generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() }
            assertTrue(messages.contains("head test file writes did not finish"), messages)
            assertTrue(messages.contains("pending count=1"), messages)
            if (trackedFile != null) {
                assertTrue(messages.contains(trackedFile), messages)
            } else {
                assertTrue(messages.contains("none tracked under"), messages)
                assertEquals(1L, delayedStarted.count, "the refusal must observe a still-delayed slot")
            }
            val sentinel = roots.single().resolve(PROBE_SENTINEL)
            assertTrue(Files.exists(sentinel), "timeout must fail before deleting the root or its contents")
            assertEquals("retained", Files.readString(sentinel))
        } finally {
            release.countDown()
            roots.forEach(::reapRoot)
        }
    }

    private fun reapRoot(root: Path) {
        assertTrue(
            writerFinished.await(PROBE_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the released writer must finish",
        )
        HeadFileWriteCleanup().awaitWrites(root)
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    /** No cleanup annotation: the nested tracked path must be named on a bounded refusal. */
    public class BlockedWrite {
        @Test
        public fun holdsWriterBeyondBody(@TempDir root: Path) {
            roots.add(root)
            Files.writeString(root.resolve(PROBE_SENTINEL), "retained")
            val target = root.resolve("nested/late-head-perf.jsonl")
            assertTrue(
                AsyncFileIo.submitFor(target) {
                    check(release.await(PROBE_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "the synthetic writer was never released"
                    }
                    Files.createDirectories(target.parent)
                    Files.writeString(target, "synthetic perf row\n")
                    writerFinished.countDown()
                },
            )
        }
    }

    /** A delayed slot counts even though it has neither a tracked path nor a runnable task yet. */
    public class DelayedWrite {
        @Test
        public fun holdsUntrackedDelayedWriterBeyondBody(@TempDir root: Path) {
            roots.add(root)
            Files.writeString(root.resolve(PROBE_SENTINEL), "retained")
            val target = root.resolve("nested/late-head-untracked.jsonl")
            assertTrue(
                AsyncFileIo.submit(delayMs = UNTRACKED_DELAY_MS) {
                    delayedStarted.countDown()
                    check(release.await(PROBE_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "the synthetic writer was never released"
                    }
                    Files.createDirectories(target.parent)
                    Files.writeString(target, "synthetic untracked row\n")
                    writerFinished.countDown()
                },
            )
        }
    }
}
