// DR-59 absence-class arms for the codex auth chain, in their own class so CodexAuthTest stays
// under detekt's LargeClass ceiling (ConfigServiceAbsenceTest precedent).
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.provider.codex.CodexAuthProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText

class CodexAuthAbsenceTest {

    private val prefetchJob = SupervisorJob()
    private val prefetchScope =
        kotlinx.coroutines.CoroutineScope(prefetchJob + kotlinx.coroutines.Dispatchers.Default)

    @AfterEach
    fun drainPrefetch() {
        prefetchScope.cancel()
    }

    // DR-59 (class law): an INACCESSIBLE auth file is not a logged-out state. exists() as a
    // pre-gate read false through an untraversable parent, so credentials() went silently null
    // and refresh() flattened to "no credential file — not logged in" while intact tokens sat
    // unreadable one chmod away.
    @Test
    fun `an inaccessible auth file is read-failed, never logged-out - DR-59`(@TempDir tmp: Path) = runTest {
        val authPath = tmp.resolve(".codex/auth.json")
        Files.createDirectories(authPath.parent)
        authPath.writeText("""{"tokens":{"access_token":"tok-1","refresh_token":"r-1"}}""")
        val log = mutableListOf<String>()
        val auth = CodexAuthProvider(
            authPath = authPath,
            authCacheMs = 60_000,
            refreshCall = { error("refresh endpoint must not be reached on a read failure") },
            prefetchScope = prefetchScope,
            log = splice.core.util.LogSink { log += it },
        )
        Files.setPosixFilePermissions(authPath.parent, PosixFilePermissions.fromString("---------"))
        try {
            assertNull(auth.credentials(), "unreadable degrades to null credentials")
            assertTrue(log.any { it.contains("failed to read") }, "the display path must log: $log")
            assertNull(auth.refresh())
        } finally {
            Files.setPosixFilePermissions(authPath.parent, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(log.any { it.contains("NOT a logged-out state") }, "ReadFailed story required: $log")
        assertTrue(log.none { it.contains("not logged in") }, "must never claim logged-out: $log")

        Files.delete(authPath)
        log.clear()
        assertNull(auth.refresh())
        assertTrue(log.any { it.contains("no credential file — not logged in") }, "true absence stays honest: $log")
    }

    // Moved from CodexAuthTest (LargeClass ceiling), then DR-59-sharpened: auth.json is SHARED
    // with the official codex CLI, so a valid-but-non-object root (a foreign writer, a
    // half-finished manual edit) must degrade, not crash — kotlinx 1.11.0 throws
    // IllegalArgumentException, which runCatchingCancellable catches by name. And it is a
    // PRESENT-but-corrupt file, so the degrade line must say "NOT a logged-out state"; the arm
    // reds if the catch list drops IllegalArgumentException OR the classification decays back
    // to logged-out wording.
    @ParameterizedTest(name = "root {0} degrades to null with the NOT-logged-out story")
    @ValueSource(strings = ["[]", "null", "\"a string\"", "42"])
    fun `a valid but non-object auth root degrades to null as a present-file problem, never a crash`(
        root: String,
        @TempDir tmp: Path,
    ) = runTest {
        val authPath = tmp.resolve(".codex/auth.json")
        Files.createDirectories(authPath.parent)
        Files.writeString(authPath, root)
        val log = mutableListOf<String>()
        val auth = CodexAuthProvider(
            authPath = authPath,
            authCacheMs = 60_000,
            refreshCall = { error("refresh must not be reached on a display read") },
            prefetchScope = prefetchScope,
            log = splice.core.util.LogSink { log += it },
        )
        assertNull(auth.credentials())
        assertTrue(log.any { it.contains("NOT a logged-out state") }, "present-corrupt must classify: $log")
        assertTrue(log.none { it.contains("not logged in") }, "must never claim logged-out: $log")
    }

    // DR-65 (codex security probe): kotlinx parse exceptions embed a "JSON input:" excerpt of the
    // parsed bytes, so a malformed auth.json that still contains a live token leaked that token
    // into daemon.log and /mgmt/auth through every $failure / toString diagnostic. The sentinel
    // rides in an UNTERMINATED file so the parser's excerpt window covers it.
    @Test
    fun `diagnostics never quote credential bytes from a malformed auth file - DR-65`(@TempDir tmp: Path) = runTest {
        val sentinel = "sk-SENTINEL-LEAK-CANARY"
        val authPath = tmp.resolve(".codex/auth.json")
        Files.createDirectories(authPath.parent)
        Files.writeString(authPath, """{"tokens":{"access_token":"$sentinel"""")
        val log = mutableListOf<String>()
        val auth = CodexAuthProvider(
            authPath = authPath,
            authCacheMs = 60_000,
            refreshCall = { error("token endpoint must not be reached on a parse failure") },
            prefetchScope = prefetchScope,
            log = splice.core.util.LogSink { log += it },
        )
        assertNull(auth.credentials())
        assertNull(auth.refresh())
        val describe = auth.describe()
        val surfaced = (log + describe.fields.map { "${it.key}=${it.value}" }).joinToString("\n")
        assertTrue(!surfaced.contains(sentinel), "credential bytes must never surface: $surfaced")
        assertTrue(log.any { it.contains("NOT a logged-out state") }, "diagnostics still classify: $log")
    }
}
