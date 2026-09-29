package splice.head.v4374

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.Knob
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: the daemon runs -Xmx2048m (features/lifecycle DEFAULT_JVM_OPTS) and the everyday one was 387 MiB resident
// (ps, Sep 28), so the requests get what is left of the heap
private const val REQUEST_HEAP_MIB = 2048 - 384
private const val CHILD_SECONDS = 300L
private const val STDERR_ECHO = 600

/** V4-374: the request cap went from 8 MiB to the Messages API's 32 MiB, and the bodies a head decodes at
 *  once are bounded by [Knob.MATERIALIZATION_PERMITS], so the two multiply. Every permit taken by a request
 *  at the cap must still be answered, in a JVM at the daemon's heap less what the rest of the daemon holds:
 *  a head that runs out of memory on the largest request its clients may send has traded a 413 for a dead
 *  daemon. Measured Sep 28: at 16 permits it does (exit 3, OutOfMemoryError at 2048 MiB). */
class RequestHeapBudgetTest {
    @Test
    fun `every materialization permit held by a request at the cap fits the heap the daemon has left for them`(
        @TempDir tmp: Path,
    ) {
        val permits = (Knob.MATERIALIZATION_PERMITS.default as Long).toInt()
        val cap = (Knob.MAX_REQUEST_BYTES.default as Long).toInt()
        val stdout = tmp.resolve("probe.stdout")
        val stderr = tmp.resolve("probe.stderr")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Xmx${REQUEST_HEAP_MIB}m",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            System.getProperty("java.class.path"),
            HeadHeapProbe::class.java.name,
            permits.toString(),
            cap.toString(),
            tmp.toString(),
        ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "not answered in $CHILD_SECONDS s")
        } finally {
            process.destroyForcibly()
        }

        val answers = Files.readAllLines(stdout)
        val failure = "$permits requests of $cap bytes: $answers ${Files.readString(stderr).take(STDERR_ECHO)}"
        assertEquals(0, process.exitValue(), failure)
        assertEquals(permits, answers.count { it.contains(": 200 ") }, answers.toString())
    }
}
