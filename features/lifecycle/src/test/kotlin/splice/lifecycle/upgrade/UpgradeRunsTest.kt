// NEW: V4-220 item 4 — a console upgrade run read off its directory: a shell that died without an exit
// is lost, a shell that has not written its pid yet is starting only while the run is young, a finished
// run is never pruned into a running one's place, and the launcher's scope runs the real shell contract.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val MINUTE_MS = 60_000L

class UpgradeRunsTest {

    private val launched = mutableListOf<List<String>>()

    private fun runs(share: Path, pid: (Path) -> Long? = { null }) = UpgradeRuns(
        EnvReader { if (it == "SPLICE_SHARE_DIR") share.toString() else null },
    ) { dir, args ->
        launched += args
        pid(dir)?.let { Files.writeString(dir.resolve(RUN_PID), it.toString()) }
        null
    }

    /** The pid of a process that has already exited. */
    private fun gone(): Long {
        val process = ProcessBuilder("true").start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.pid()
    }

    @Test
    fun `a shell that died without writing its exit is lost, not running`(@TempDir share: Path) {
        val dead = gone()
        val started = runs(share) { dead }.start(UpgradeRequest(null, false))
        assertTrue(started is UpgradeRunStart.Started, "$started")
        assertEquals(UpgradeRunState.LOST, runs(share).latest()?.state)
        assertTrue(runs(share).start(UpgradeRequest(null, false)) is UpgradeRunStart.Started, "a lost run held")
    }

    @Test
    fun `a run with no pid yet is starting while young and lost once old`(@TempDir share: Path) {
        runs(share).start(UpgradeRequest(null, false))
        val dir = Files.list(share.resolve("upgrade-runs")).use { it.toList() }.single()
        assertEquals(UpgradeRunState.RUNNING, runs(share).latest()?.state)
        val old = System.currentTimeMillis() - MINUTE_MS
        val stamp = Regex("\"started_at_epoch_millis\":\\d+")
        val record = Files.readString(dir.resolve(RUN_FILE)).replace(stamp, "\"started_at_epoch_millis\":$old")
        Files.writeString(dir.resolve(RUN_FILE), record)
        assertEquals(UpgradeRunState.LOST, runs(share).latest()?.state)
    }

    @Test
    fun `the newest five runs are kept and older finished ones go`(@TempDir share: Path) {
        val daemon = runs(share)
        val ids = (1..7).map {
            val started = daemon.start(UpgradeRequest(null, false)) as UpgradeRunStart.Started
            Files.writeString(share.resolve("upgrade-runs/${started.run.id}/$RUN_EXIT"), "0")
            started.run.id
        }
        val left = Files.list(share.resolve("upgrade-runs")).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(ids.takeLast(5), left.sorted())
        assertEquals(ids.last(), runs(share).latest()?.id)
        assertTrue(launched.all { "--now" !in it }, "$launched")
    }

    /** The scope's argv, then its tail (the run's shell) run for real under /bin/sh, as systemd-run
     *  --scope would run it: pid first, the command's output in the run's log, the exit code last. */
    @Test
    fun `the scope runs the upgrade under the run's own shell, and its files are the contract`(@TempDir dir: Path) {
        val calls = mutableListOf<List<String>>()
        val spawned = mutableListOf<Process>()
        val shellOnly = DetachedSpawn { command, log ->
            calls += command
            val shell = command.drop(command.indexOf("--") + 1)
            ProcessBuilder(shell).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start().also { spawned += it }
        }
        val scope = SystemdScope("/bin/echo", Path.of("/share/splice.jar"), shellOnly)
        assertEquals(null, scope.launch(dir, listOf("upgrade", "--to", "v1.2.3")))
        assertEquals(
            listOf(
                "systemd-run", "--user", "--scope", "--collect", "--quiet", "--unit=splice-upgrade-${dir.fileName}",
                "--", "/bin/sh", "-c", RUN_SCRIPT, "sh", dir.toString(),
                "/bin/echo", "-jar", "/share/splice.jar", "upgrade", "--to", "v1.2.3",
            ),
            calls.single(),
        )
        assertTrue(spawned.single().waitFor(10, TimeUnit.SECONDS), "the run's shell did not end")
        assertEquals("0", Files.readString(dir.resolve(RUN_EXIT)).trim())
        assertTrue(Files.readString(dir.resolve(RUN_PID)).trim().toLong() > 0)
        assertEquals("-jar /share/splice.jar upgrade --to v1.2.3", Files.readAllLines(dir.resolve(RUN_OUTPUT)).single())
        assertFalse(Files.exists(dir.resolve("$RUN_EXIT.tmp")))
    }

    /** systemd-run gone before the shell wrote its pid never started the run, and says why. */
    @Test
    fun `a scope that could not start reports its exit and its last line`(@TempDir dir: Path) {
        val noBus = DetachedSpawn { _, log ->
            ProcessBuilder("/bin/sh", "-c", "echo 'Failed to connect to bus' >&2; exit 1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
        }
        val scope = SystemdScope("java", Path.of("/j"), noBus)
        assertEquals("systemd-run exited 1 (Failed to connect to bus)", scope.launch(dir, listOf("upgrade")))
    }
}
