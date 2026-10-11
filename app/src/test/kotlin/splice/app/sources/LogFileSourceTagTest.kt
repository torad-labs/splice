// The per-head log tail catches every head-scoped producer: the auth probe, a provider refresh line and a boot failure.
package splice.app.sources

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.AuthProbeLoop
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.HeadScopedLogs
import splice.diagnostics.logs.LogFileSource
import java.nio.file.Files
import java.nio.file.Path

class LogFileSourceTagTest {

    private object DeadAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = null
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe() = AuthDescription(present = false, kind = "fake")
    }

    @Test
    fun `every head-scoped producer shape survives the per-head tail filter`(@TempDir tmp: Path) = runTest {
        val captured = mutableListOf<String>()

        // 1. The REAL auth-probe producer (pre-fix: "[auth-probe:claudex] ..." — invisible).
        AuthProbeLoop("claudex", DeadAuth, log = captured::add).probeOnce()

        // 2. A provider refresh line through the JW-03 injection wrapper (pre-fix: bare
        //    "[codex-auth] ..." — invisible).
        HeadScopedLogs.headScopedLog("claudex", captured::add)("[codex-auth] refresh failed: invalid_grant\n")

        // 3. The boot-failure shape assembleDaemonHeads emits (pre-fix: "[daemon] head 'claudex'
        //    ..." — invisible; the kt-head-log-prefix wall bans that shape at write time).
        captured.add("[claudex][boot] SKIPPED (build failed): bad base_url\n")

        val logFile = tmp.resolve("daemon.log")
        Files.writeString(logFile, captured.joinToString("") + "[other] unrelated head line\n")

        val tail = LogFileSource(logFile, "[claudex]").tail(50)
        assertEquals(3, tail.lines().count { it.isNotBlank() }, "expected all three producers:\n$tail")
        assertTrue(tail.contains("[claudex][auth-probe] initial health check: unhealthy"), tail)
        assertTrue(tail.contains("[claudex][codex-auth] refresh failed"), tail)
        assertTrue(tail.contains("[claudex][boot] SKIPPED"), tail)
        assertTrue(!tail.contains("[other]"), "foreign heads stay filtered: $tail")
    }
}
