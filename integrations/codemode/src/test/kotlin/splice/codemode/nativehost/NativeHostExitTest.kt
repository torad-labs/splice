// Goal (Oct 29, 2026, splice-lead, Marcos: "fix everything"): a splice JVM leaves no unpacked code-mode host behind
// when it ends. Every JVM that runs code mode unpacks the jar's pinned host, about 77 MB, into its own
// /tmp/splice-native-* folder, and only an explicit close removed it, which the daemon never reaches: on Oct 29 246
// such folders held 12.6 GB of /tmp, one per daemon start and per test JVM since Oct 24. A JVM that ends normally or
// on SIGTERM, which is how systemd and `splice restart` stop the daemon, now removes its folder. Driven through a real
// child JVM whose temp folder is the test's own.
package splice.codemode.nativehost

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NativeHostExitTest {
    private fun probe(tmp: Path, mode: String): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.io.tmpdir=$tmp",
        "-cp",
        System.getProperty("java.class.path"),
        NativeHostExitProbe::class.java.name,
        mode,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    private fun unpacked(tmp: Path): List<String> =
        Files.list(tmp).use { entries -> entries.map { it.fileName.toString() }.toList() }
            .filter { it.startsWith("splice-native-") }

    /** The host path the probe printed, which must lie in [tmp], so the test watches the probe's own folder. */
    private fun printed(process: Process, tmp: Path): Path {
        val line = checkNotNull(process.inputStream.bufferedReader().readLine()) { "the probe printed no host path" }
        val host = Path.of(line)
        assertTrue(host.startsWith(tmp), "the probe unpacked outside its temp folder: $host")
        return host
    }

    @Test
    @Timeout(120)
    fun `a JVM that ends without closing the host leaves no unpacked host behind`(@TempDir tmp: Path) {
        val process = probe(tmp, "exit")
        printed(process, tmp)
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the probe did not end")

        assertEquals(0, process.exitValue())
        assertEquals(emptyList<String>(), unpacked(tmp))
    }

    @Test
    @Timeout(120)
    fun `a JVM stopped by SIGTERM while its host is unpacked leaves no unpacked host behind`(@TempDir tmp: Path) {
        val process = probe(tmp, "wait")
        val host = printed(process, tmp)
        assertTrue(Files.isExecutable(host), "the host was not unpacked before the stop")

        process.destroy()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the probe did not end on SIGTERM")

        assertEquals(emptyList<String>(), unpacked(tmp))
    }
}
