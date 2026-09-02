// NEW (JW-01): the boot-failure net. A pre-logger boot throwable (broken TOML, unwritable state
// dir) used to die in /dev/null — both cold-start paths discarded the JVM's output and
// runDaemon parsed the topology before the log sink existed. bootFailureHandler is installed
// FIRST and writes SYNCHRONOUSLY (the async lane dies with the JVM).
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

class DaemonBootFailureTest {

    private val process = DaemonProcess()

    // DR-170 — this arm asserted the OPPOSITE until 2026-09-01, and the inversion is deliberate.
    // It demanded that "Invalid TOML at line 7" and the exception class both SURVIVE into
    // daemon.log. That was JW-01's intent written before DR-65 existed, and the two collide
    // exactly here: this handler is installed BEFORE TopologyLoader runs, ktoml quotes the
    // offending splice.toml LINE in its message, and DR-92 established that splice.toml legally
    // carries credential-like values in extra_headers. The fixture's own message is therefore the
    // hazard, and an assertion requiring it to survive was pinning the leak in place — the reason
    // the site sat unnoticed with a green suite over it.
    //
    // DR-65 outranks. The message is withheld; JW-01's purpose — a boot failure must not die in
    // /dev/null — is met by the FRAMES, which name the failing call chain without quoting a byte
    // of any file. The class name goes with the message by SafeFailureText's own design, which
    // reaches it only through an overridable toString().
    // Two arms rather than one, because the row makes two claims that fail independently: withhold
    // the message, and STILL diagnose. A single arm holding both reds identically for either
    // regression, so nothing downstream could tell a leak from a silent net.
    /** Drives the handler over a ktoml-shaped failure and returns what daemon.log received.
     *  Synchronous by contract: readable IMMEDIATELY, there is no async lane to drain. */
    private fun bootLog(tmp: Path): String {
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        val boom = IllegalStateException("ktoml: Invalid TOML at line 7: unexpected ']'")
        process.bootFailureHandler(statePaths).uncaughtException(Thread.currentThread(), boom)
        val log = statePaths.logsDir.resolve("daemon.log")
        assertTrue(Files.exists(log), "daemon.log must exist after a boot failure")
        return Files.readString(log)
    }

    @Test
    fun `a boot throwable is logged without its message - DR-170`(@TempDir tmp: Path) {
        val content = bootLog(tmp)
        assertFalse(content.contains("Invalid TOML at line 7"), "a parse error quotes config bytes: $content")
        assertFalse(content.contains("unexpected"), "no fragment of the message may survive: $content")
        assertTrue(content.contains("message withheld"), "the operator must be told it was withheld: $content")
    }

    @Test
    fun `the boot net still names the failing call chain - DR-170`(@TempDir tmp: Path) {
        // JW-01 has to stay TRUE, not merely safe. Withholding everything would satisfy DR-65 and
        // restore the /dev/null outcome the boot net was built to end, so the frames are pinned.
        assertTrue(bootLog(tmp).contains("DaemonBootFailureTest"), "the frames must name the call chain")
    }

    @Test
    fun `the net itself survives an unwritable logs dir`(@TempDir tmp: Path) {
        // The handler must never throw out of a dying thread — a read-only logs dir degrades to
        // the stderr copy alone.
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(statePaths.logsDir)
        java.nio.file.attribute.PosixFilePermissions.fromString("r-x------").let {
            Files.setPosixFilePermissions(statePaths.logsDir, it)
        }
        try {
            process.bootFailureHandler(statePaths).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        } finally {
            Files.setPosixFilePermissions(
                statePaths.logsDir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
        }
        // reaching here without a throw IS the assertion
    }
}
