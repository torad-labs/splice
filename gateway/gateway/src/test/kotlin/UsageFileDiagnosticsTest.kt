// DR-73 (invariant audit): the usage-ring and ratelimit state readers logged the raw parse
// throwable/message — state-file bytes rode "JSON input:" excerpts into daemon.log + /mgmt/logs.
// Both keep their degrade shape (empty / null, one line per episode); only the render is sealed.
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.gateway.usage.RateLimitFile
import splice.gateway.usage.UsageRingFile
import java.nio.file.Files
import java.nio.file.Path

class UsageFileDiagnosticsTest {

    @Test
    fun `ratelimit diagnostics never quote state bytes - DR-73`(@TempDir tmp: Path) {
        val sentinel = "SENTINEL-RL-BYTES"
        val file = tmp.resolve("ratelimit.json")
        Files.writeString(file, """{"limit":"$sentinel""")
        val log = mutableListOf<String>()
        assertNull(RateLimitFile(file, LogSink { log += it }).read())
        val joined = log.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "state bytes must never surface: $joined")
        assertTrue(log.any { it.contains("unreadable") }, joined)
    }

    @Test
    fun `usage-ring diagnostics never quote state bytes - DR-73`(@TempDir tmp: Path) {
        val sentinel = "SENTINEL-RING-BYTES"
        val file = tmp.resolve("usage.json")
        Files.writeString(file, """[{"t":"$sentinel""")
        val log = mutableListOf<String>()
        assertTrue(UsageRingFile(file, Any(), LogSink { log += it }).readEntriesFromDisk().isEmpty())
        val joined = log.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "state bytes must never surface: $joined")
        assertTrue(log.any { it.contains("unreadable/corrupt") }, joined)
    }

    // DR-139: the WRITE half of the same law. DR-73 named UsageRingFile in its sink list and sealed
    // the READ render at :67, but the persist-failure line kept `failure.message`. Its exact twin —
    // RateLimitStore's "[usage] ratelimit flush FAILED" — renders through SafeFailureText, so the
    // intended form is twin-proven. Nothing leaks today: every throwable SecureFile can raise is a
    // FileSystemException, which render() allowlists. The point of the renderer is that a future
    // wrapped or custom exception cannot START quoting file bytes, and `.message` defeats that
    // silently. The assertion keys on the class-qualified text render() emits via toString(), which
    // raw `.message` never produces.
    @Test
    fun `usage-ring persist failures render through the sanitizer - DR-139`(@TempDir tmp: Path) {
        // A non-empty DIRECTORY at the usage path: the atomic move onto it fails with
        // DirectoryNotEmptyException — the only persist failure reachable without inventing a seam.
        val usageFile = Files.createDirectories(tmp.resolve("usage.json"))
        Files.writeString(usageFile.resolve("occupant"), "x")
        val log = mutableListOf<String>()
        UsageRingFile(usageFile, Any(), LogSink { log += it }).persistSnapshot(emptyList(), 1L)
        val joined = log.joinToString("\n")
        assertTrue(joined.contains("persist FAILED"), "a failed write must leave a trace: $joined")
        assertTrue(
            joined.contains("java.nio.file."),
            "the cause must render through SafeFailureText, not raw failure.message: $joined",
        )
    }
}
