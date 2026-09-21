// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. Its comment block and its body
// arrive verbatim; the split's acceptance is that :providers-grok:test reports the same total before
// and after.
package splice.provider.grok

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// DR-151: BS-2's arm above proves the FALLBACK — a lost write still serves the current token — but
// says nothing about the LINE the operator reads on the way, which is where a leak would hide.
//
// This arm deliberately does NOT inject the write. An earlier draft added a
// `write: (Path, String) -> Unit` seam so a test could throw an arbitrary throwable; two walls
// rejected it and both were right. kt-no-lambda-seam bans the raw function type outright, and
// SH-10 requires the atomic 0600 primitive to be provably WHAT PERSISTS the credential — any
// injection point, fun interface or not, makes the real writer a runtime choice and reopens
// exactly the world-readable window #924 extracted SecureFile to make inexpressible. Trading a
// security invariant for test convenience on a credential write is the wrong direction, so the
// throwable here is one production actually produces: a denied parent directory.
//
// The withholding property itself lives at the sink and is proven there, with mutants, by
// PersistFailedRenderTest. What this arm adds is that the provider REACHES that sink, and that the
// line it produces never carries the credential.
class GrokPersistLinePrivacyTest {

    @Test
    fun `a failed persist logs its line without ever quoting the credential - DR-151`() = runTest {
        val dir = Files.createTempDirectory("grok-persist-line")
        val now = 1_000_000L
        val file = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"tokens":{"access_token":"grok-access","refresh_token":"grok-refresh"},
                "expires":${now + 10_000}}""",
        )
        val lines = mutableListOf<String>()
        val auth = GrokAuthProvider(
            authPath = file,
            authCacheMs = 30_000L,
            clock = { now },
            nowIso = { "iso-now" },
            refreshCall = {
                RefreshAttempt.Granted(GrokRefreshedTokens("new-access", "rotated-secret", expiresIn = 21_600))
            },
            log = LogSink { lines.add(it) },
        )
        Files.createFile(file.resolveSibling("${file.fileName}.lock"))
        val writable = Files.getPosixFilePermissions(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        val served = try {
            auth.credentials()
        } finally {
            Files.setPosixFilePermissions(file.parent, writable)
        }
        assertEquals("grok-access", (served as Credentials.Bearer).token, "BS-2 fallback must still hold")

        val persist = lines.single { it.contains("persist failed") }
        assertFalse(persist.contains("rotated-secret"), "the rotated token must never reach the log: $persist")
        assertFalse(persist.contains("grok-refresh"), "nor the old refresh token: $persist")
        assertTrue(persist.contains("credential write failed"), "the typed Write branch must be what fired: $persist")
    }
}
