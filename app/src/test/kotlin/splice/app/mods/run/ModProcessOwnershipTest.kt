package splice.app.mods.run

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ModProcessOwnershipTest {
    @TempDir lateinit var root: Path

    private val log = LogSink {}
    private val self = ProcessHandle.current().pid()

    @Test
    fun `a stopped host's resistant helper is gone before a new run may take ownership`() {
        val command = ProcessBuilder("sh", "-c", "trap '' TERM; sleep 600 & echo \$!; read unused")
        val guard = ModHostScript(root).install().resolveSibling("guard.pl")
        val guardian = ModProcessGroup.isolate(command, guard).start()
        var helper: ProcessHandle? = null
        try {
            val records = ModRunRecords(root, log)
            val leader = identity(guardian)
            records.turn("mod").use { records.begin("mod", leader) }
            ModProcessGroup.admit(guardian)
            val pid = guardian.inputStream.bufferedReader().readLine().trim().toLong()
            helper = ProcessHandle.of(pid).orElseThrow()
            assertTrue(helper.isAlive)
            assertThrows(IOException::class.java) { records.turn("mod").use { records.requireGone("mod") } }
            val previous = checkNotNull(records.previous("mod"))
            guardian.outputStream.close()
            runBlocking { records.awaitPrevious(previous) }
            assertTrue(guardian.waitFor(10, TimeUnit.SECONDS))
            assertFalse(helper.isAlive)
            records.turn("mod").use { records.requireGone("mod") }
        } finally {
            guardian.destroy()
            helper?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
        }
    }

    @Test
    fun `a recycled group leader is left alone even with its pid in the old record`() {
        withUnrelatedProcess { unrelated ->
            val live = identity(unrelated)
            val earlier = live.copy(birth = live.birth.minusSeconds(3600))
            val records = records(mapOf(self to 1L, live.pid to live.pid))
            records.turn("mod").use { records.begin("mod", earlier) }
            runBlocking { records.reap(except = emptySet()) }
            assertTrue(unrelated.isAlive)
            assertFalse(Files.exists(root.resolve("run/mod.json")))
            assertNull(records.previous("mod"))
        }
    }

    @Test
    fun `a group with no verified leader retains ownership and no process is signalled`() {
        withUnrelatedProcess { unrelated ->
            // Simulate a group whose reused leader has already exited. Its surviving member is real.
            val missing = 2_000_000_000L
            val previous = ModIdentity(missing, identity(unrelated).birth.minusSeconds(3600))
            val records = records(mapOf(self to 1L, unrelated.pid() to missing))
            records.turn("mod").use { records.begin("mod", previous) }
            assertEquals(previous, records.previous("mod")?.leader)
            assertThrows(IOException::class.java) { records.turn("mod").use { records.requireGone("mod") } }
            assertTrue(unrelated.isAlive)
        }
    }

    @Test
    fun `an unreadable process table keeps ownership`() {
        val records = records(null)
        records.turn("mod").use {
            records.begin("mod", ModIdentity(self, java.time.Instant.EPOCH))
        }
        runBlocking { records.reap(except = emptySet()) }
        assertTrue(Files.exists(root.resolve("run/mod.json")))
        assertThrows(IOException::class.java) { records.turn("mod").use { records.requireGone("mod") } }
    }

    @Test
    fun `an unreadable record is not treated as no run`() {
        Files.createDirectories(root.resolve("run"))
        Files.writeString(root.resolve("run/mod.json"), "{not json")
        val records = ModRunRecords(root, log)
        assertThrows(IOException::class.java) { records.turn("mod").use { records.requireGone("mod") } }
    }

    @Test
    fun `the start turn is held until the first holder releases it`() {
        val locks = ModStartLocks(root.resolve("run"))
        checkNotNull(locks.acquire("mod", 50)).use {
            assertNull(locks.acquire("mod", 50))
        }
        checkNotNull(locks.acquire("mod", 50)).close()
    }

    private fun identity(process: Process): ModIdentity = checkNotNull(ModProcessIdentity.of(process.toHandle()))

    private fun records(groups: Map<Long, Long>?): ModRunRecords =
        ModRunRecords(root, log, ModGroupOwnership { groups })

    private fun withUnrelatedProcess(test: (Process) -> Unit) {
        val process = ProcessBuilder("/bin/sleep", "60").start()
        try {
            test(process)
        } finally {
            process.destroyForcibly().waitFor()
        }
    }
}
