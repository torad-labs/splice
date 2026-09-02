// NEW (JW-03): the per-head log tail must catch every head-scoped producer. The tail filters
// daemon.log on the literal `[<headKey>]`; the legacy shapes ([auth-probe:key], [codex-auth],
// [daemon] head 'key' ...) never contained it, so auth/refresh/boot diagnostics vanished from
// the one view built to show them. The probe line here comes from the REAL producer.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.AuthProbeLoop
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.HeadScopedLogs
import java.nio.file.Files
import java.nio.file.Path

class LogFileSourceTagTest {

    private object DeadAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = null
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe() = AuthDescription(present = false, kind = "fake")
    }

    @Test
    fun `every head-scoped producer shape survives the per-head tail filter - JW-03`(@TempDir tmp: Path) = runTest {
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

        val tail = splice.app.LogFileSource(logFile, "[claudex]").tail(50)
        assertEquals(3, tail.lines().count { it.isNotBlank() }, "expected all three producers:\n$tail")
        assertTrue(tail.contains("[claudex][auth-probe] initial health check: unhealthy"), tail)
        assertTrue(tail.contains("[claudex][codex-auth] refresh failed"), tail)
        assertTrue(tail.contains("[claudex][boot] SKIPPED"), tail)
        assertTrue(!tail.contains("[other]"), "foreign heads stay filtered: $tail")
    }
}
