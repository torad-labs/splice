package splice.head.v4374

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.Knob
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val DAEMON_HEAP_MIB = 2048
private const val CHILD_SECONDS = 300L
private const val STDERR_ECHO = 600

/** The real head answers concurrent bursts in the daemon's heap, beside a held resident allowance.
 *  The sender streams bodies and the upstream discards them, so the probe exercises actual decode,
 *  parse, translation and admission rather than multiplying a hand-authored request count. */
class RequestHeapBudgetTest {
    @Test
    fun `sixteen requests at the body cap survive the weighted heap budget`(@TempDir tmp: Path) {
        probe(16, (Knob.MAX_REQUEST_BYTES.default as Long).toInt(), tmp)
    }

    @Test
    fun `fifty everyday requests survive the weighted heap budget`(@TempDir tmp: Path) {
        probe(50, 2 * 1024 * 1024, tmp)
    }

    private fun probe(requests: Int, bytes: Int, tmp: Path) {
        val stdout = tmp.resolve("probe.stdout")
        val stderr = tmp.resolve("probe.stderr")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Xmx${DAEMON_HEAP_MIB}m",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            System.getProperty("java.class.path"),
            HeadHeapProbe::class.java.name,
            requests.toString(),
            bytes.toString(),
            tmp.toString(),
        ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "not answered in $CHILD_SECONDS s")
        } finally {
            process.destroyForcibly()
        }
        val answers = Files.readAllLines(stdout)
        val failure = "$requests requests of $bytes bytes: $answers ${Files.readString(stderr).take(STDERR_ECHO)}"
        assertEquals(0, process.exitValue(), failure)
        assertEquals(requests, answers.count { it.contains(": 200 ") }, answers.toString())
    }
}
