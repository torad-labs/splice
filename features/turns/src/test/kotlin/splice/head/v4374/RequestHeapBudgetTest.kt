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

/** The real head answers concurrent bursts in the daemon's heap, beside a held resident allowance.
 *  The sender streams bodies and the upstream discards them, so the probe exercises actual decode,
 *  parse, translation and admission rather than multiplying a hand-authored request count. */
class RequestHeapBudgetTest {
    @Test
    fun `sixteen requests at the body cap survive the weighted heap budget`(@TempDir tmp: Path) {
        probe(16, Knob.MAX_REQUEST_BYTES.count().toInt(), tmp)
    }

    @Test
    fun `fifty everyday requests survive the weighted heap budget`(@TempDir tmp: Path) {
        probe(50, 2 * 1024 * 1024, tmp)
    }

    @Test
    fun `139 concurrent requests across two heads survive a 256 MiB process heap`(@TempDir tmp: Path) {
        probe(139, 1024 * 1024, tmp, heapMiB = 256)
    }

    private fun preserveProbe(tmp: Path) {
        val captureRoot = System.getProperty("splice.heapProbeCaptureRoot") ?: return
        val capture = Path.of(captureRoot).resolve(tmp.fileName.toString())
        Files.createDirectories(capture)
        Files.setPosixFilePermissions(
            capture,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
        listOf("probe.hprof", "probe.stdout", "probe.stderr", "probe.gc.log").forEach { name ->
            val source = tmp.resolve(name)
            if (Files.exists(source)) {
                val kept = Files.copy(source, capture.resolve(name))
                Files.setPosixFilePermissions(
                    kept,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
        println("probe evidence: $capture")
    }

    private fun probe(requests: Int, bytes: Int, tmp: Path, heapMiB: Int = DAEMON_HEAP_MIB) {
        val stdout = tmp.resolve("probe.stdout")
        val stderr = tmp.resolve("probe.stderr")
        val dump = tmp.resolve("probe.hprof")
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Xmx${heapMiB}m",
            "-XX:ActiveProcessorCount=4",
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=$dump",
            "-Xlog:gc+heap=debug:file=${tmp.resolve("probe.gc.log")}:uptime,level,tags",
            "-Djdk.httpclient.HttpClient.log=errors",
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
            preserveProbe(tmp)
        }
        val answers = Files.readAllLines(stdout)
        answers.filter { it.startsWith("heap observed_peak_bytes=") }.forEach(::println)
        val failure = "$requests requests of $bytes bytes: $answers ${Files.readString(stderr)}"
        assertEquals(0, process.exitValue(), failure)
        assertEquals(requests, answers.count { it.contains(": 200 ") || it.contains(": 529 ") }, answers.toString())
        assertTrue(answers.any { it.contains(": 200 ") }, "at least one real turn must finish")
    }
}
