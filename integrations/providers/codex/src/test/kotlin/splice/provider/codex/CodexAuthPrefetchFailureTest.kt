// NEW: a real lock-open failure in the queued prefetch must reach the head log, not a later test.
package splice.provider.codex

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class CodexAuthPrefetchFailureTest {
    @Test
    @Timeout(60)
    fun `background lock failure is safely logged and never escapes`(@TempDir dir: Path) = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val escaped = mutableListOf<Throwable>()
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, failure -> escaped += failure },
        )
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":1120}""".toByteArray())
        val access = "e30.$payload.synthetic"
        val file = dir.resolve("auth.json")
        Files.writeString(file, """{"tokens":{"access_token":"$access","refresh_token":"synthetic-refresh"}}""")
        val auth = CodexAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { 1_000_000L },
            prefetchScope = scope,
            refreshCall = { error("obstructed lock must prevent the exchange") },
            log = { logs += it },
        )
        try {
            assertEquals(Credentials.Bearer(access, null), auth.credentials())
            assertEquals(1, scope.coroutineContext.job.children.count(), "refresh is queued, not awaited")
            // Removing auth before launch is typed absence. An unreadable sibling lock instead
            // reaches FileChannel.open outside the typed refresh outcomes, deterministically.
            Files.createDirectory(file.resolveSibling("auth.json.lock"))
            while (scope.coroutineContext.job.children.any()) {
                scheduler.runCurrent()
                yield()
            }
            assertTrue(escaped.isEmpty(), "prefetch must not escape to CoroutineExceptionHandler: $escaped")
            assertEquals(1, logs.count { it.startsWith("[codex-auth] background refresh failed: ") })
        } finally {
            scope.cancel()
            scheduler.runCurrent()
        }
    }
}
