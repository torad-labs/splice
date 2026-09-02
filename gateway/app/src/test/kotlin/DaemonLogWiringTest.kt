// NEW: the production log path had ZERO coverage (review of PR #62, 2026-07-27) — and the gap was
// not theoretical: the newline regression below shipped through a fully green suite.
//
// Two things were untested and both are load-bearing for /mgmt/logs:
//   1. persistentLogger's OUTPUT SHAPE. It writes daemon.log, which ControlServer.logsJson splits
//      on "\n". The kt-no-println conversion moved 14 sites off System.err.println (which appends
//      the terminator) onto this sink (which did not), so their entries merged into one run-on
//      line — the endpoint this change exists to feed emitting concatenated garbage.
//   2. The Main-install -> DaemonLog::write -> nine-provider-default WIRING. A broken install or a
//      typo'd default is invisible to every other test, because every other test injects its own
//      sink and never exercises the default at all.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.app.logStamp
import splice.core.config.KeyStore
import splice.core.util.AsyncFileIo
import splice.core.util.DaemonLog
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.readText

class DaemonLogWiringTest {

    private val process = DaemonProcess()

    private fun drainToDisk() = assertTrue(AsyncFileIo.drain(), "async file lane did not drain")

    @Test
    fun `each message becomes exactly one daemon-log line, with or without a trailing newline`(
        @TempDir logs: Path,
    ) {
        val log = process.persistentLogger(logs)
        // The two conventions that now coexist: pre-existing callers terminate their own message,
        // the 14 converted kt-no-println sites do not (System.err.println used to do it for them).
        log("[a] caller that terminates its own line\n")
        log("[b] converted site, no terminator")
        log("[c] another converted site")
        drainToDisk()

        val lines = Files.readAllLines(logs.resolve("daemon.log"))
        assertEquals(3, lines.size, "one message must be one line — a missing terminator used to merge them")
        assertTrue(lines[0].endsWith("[a] caller that terminates its own line"), lines[0])
        assertTrue(lines[1].endsWith("[b] converted site, no terminator"), lines[1])
        assertTrue(lines[2].endsWith("[c] another converted site"), lines[2])
    }

    @Test
    fun `a message is never double-terminated`(@TempDir logs: Path) {
        process.persistentLogger(logs)("[x] already terminated\n")
        drainToDisk()
        val raw = logs.resolve("daemon.log").readText()
        assertTrue(raw.endsWith("\n"), "must end with a terminator")
        assertTrue(!raw.endsWith("\n\n"), "must not double-terminate a caller that supplied one")
    }

    @Test
    fun `DaemonLog routes to the installed sink — the default nine providers rely on`() {
        val seen = mutableListOf<String>()
        try {
            // Uninstalled it is a no-op: a component that wants output injects a sink, and an
            // un-installed process must never silently fall back to stderr.
            DaemonLog.write("[dropped] before install")
            assertEquals(emptyList<String>(), seen)

            DaemonLog.install { seen += it }
            DaemonLog.write("[kept] after install")
            assertEquals(listOf("[kept] after install"), seen, "install -> write is the production path")
        } finally {
            // The sink is process-wide; leaving a test's capture installed would leak into every
            // later test in this JVM.
            DaemonLog.install {}
        }
    }

    @Test
    fun `the provider default resolves to DaemonLog, so the daemon wiring is one hop not two`(
        @TempDir tmp: Path,
    ) = runTest {
        val seen = mutableListOf<String>()
        try {
            DaemonLog.install { seen += it }
            val unreadable = Files.createDirectory(tmp.resolve("key-directory"))
            val provider = ApiKeyAuthProvider(
                envVar = "DR33_API_KEY",
                keyFile = unreadable,
                envReader = { null },
                keyStore = KeyStore(tmp.resolve("keys.toml")),
            )

            assertEquals(false, provider.describe().present)
            assertEquals(1, seen.size)
            assertTrue(seen.single().startsWith("[api-key-auth] failed to read $unreadable:"), seen.single())
        } finally {
            DaemonLog.install {}
        }
    }

    // 3. The STAMP. daemon.log rotates by size, so one file spans days with no marker between them,
    // and a line lifted out of it — a grep hit, a quote in a report — has to say which day it is.
    // Audit 2026-09-02: 265,321 lines over four days, and a reader attributed one day's watchdog
    // stalls to the next day's build because nothing on the line said otherwise.
    @Test
    fun `a written line dates itself, so a line read alone names its day`(@TempDir logs: Path) {
        process.persistentLogger(logs)("[claudex] turn compact=true latency=336431ms ok")
        drainToDisk()

        val line = Files.readAllLines(logs.resolve("daemon.log")).single()
        assertTrue(
            Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}] \[claudex]""").containsMatchIn(line),
            "a log line must carry a full date and a fixed-width clock, got: $line",
        )
    }

    // Width is fixed, not merely dated: LocalTime/LocalDateTime.toString() OMIT the seconds field
    // when it is zero, which stamped 4,339 live lines "[13:47]" and broke every column-oriented
    // read. Pinned at a zero second because that is the only instant that used to differ.
    @Test
    fun `a zero second keeps its field, so every stamp is the same width`() {
        assertEquals("2026-09-02 13:47:00", logStamp.format(LocalDateTime.of(2026, 9, 2, 13, 47, 0)))
        assertEquals("2026-09-02 13:47:12", logStamp.format(LocalDateTime.of(2026, 9, 2, 13, 47, 12)))
    }
}
