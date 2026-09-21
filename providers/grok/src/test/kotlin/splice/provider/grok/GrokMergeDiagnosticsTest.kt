// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. It arrives with its comments and its
// body byte-for-byte, and the split's acceptance is that :providers-grok:test reports the same total
// before and after — a dropped case would leave detekt green and the suite green and show up in
// nothing but that number.
package splice.provider.grok

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.RefreshAttempt
import java.nio.file.Files

// DR-73 (invariant audit): the persist-side merge re-read is the one credential parse DR-65 did
// not seal on grok — a file gone malformed between the pre-refresh read and the persist quoted
// its bytes through the raw throwable. The refreshCall corrupts the file mid-flow (the :311
// peer-rotation interleave idiom) so the merge re-read fails on real bytes.
class GrokMergeDiagnosticsTest {

    @Test
    fun `merge diagnostics never quote credential bytes - DR-73`() = runTest {
        val sentinel = "xai-SENTINEL-MERGE-LEAK"
        val dir = Files.createTempDirectory("grok-merge-leak")
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"tokens":{"access_token":"acc","refresh_token":"R1"}}""")
        val log = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { 1_000_000L },
            refreshCall = {
                Files.writeString(file, """{"tokens":{"access_token":"$sentinel""")
                RefreshAttempt.Granted(splice.provider.grok.GrokRefreshedTokens("new-access", "new-refresh", 3600))
            },
            log = splice.core.util.LogSink { log += it },
        )
        auth.refresh()
        val joined = log.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "credential bytes must never surface: $joined")
        assertTrue(log.any { it.contains("merge failed") }, "the merge degrade must log: $joined")
    }
}
