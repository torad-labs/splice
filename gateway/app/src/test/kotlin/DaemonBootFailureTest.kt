// NEW (JW-01): the boot-failure net. A pre-logger boot throwable (broken TOML, unwritable state
// dir) used to die in /dev/null — both cold-start paths discarded the JVM's output and
// runDaemon parsed the topology before the log sink existed. bootFailureHandler is installed
// FIRST and writes SYNCHRONOUSLY (the async lane dies with the JVM).
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.bootFailureHandler
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

class DaemonBootFailureTest {

    @Test
    fun `a boot throwable lands in daemon log synchronously - JW-01`(@TempDir tmp: Path) {
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        val handler = bootFailureHandler(statePaths)
        val boom = IllegalStateException("ktoml: Invalid TOML at line 7: unexpected ']'")

        handler.uncaughtException(Thread.currentThread(), boom)

        // Synchronous by contract: readable IMMEDIATELY, no async lane to drain.
        val log = statePaths.logsDir.resolve("daemon.log")
        assertTrue(Files.exists(log), "daemon.log must exist after a boot failure")
        val content = Files.readString(log)
        assertTrue(content.contains("Invalid TOML at line 7"), "the parse error must survive: $content")
        assertTrue(content.contains("IllegalStateException"), "the class must survive: $content")
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
            bootFailureHandler(statePaths).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        } finally {
            Files.setPosixFilePermissions(
                statePaths.logsDir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
        }
        // reaching here without a throw IS the assertion
    }
}
