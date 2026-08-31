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
}
