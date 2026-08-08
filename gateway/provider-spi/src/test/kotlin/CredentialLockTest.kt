// NEW (G1): CredentialLock pins — round-trip + release, same-JVM serialization (the intra-process
// Mutex half: a pure FileLock would throw OverlappingFileLockException on a same-JVM overlap rather
// than queue), and the "never lock the credential file itself" contract (the lock is a SIBLING file).
// Mirrors SingleFlightTest's placement (default package) and the runBlocking + launch + yield idiom.
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.CredentialLock
import java.nio.file.Files

class CredentialLockTest {

    @Test
    fun `withLock returns the block's result and releases the lock for the next caller`() = runTest {
        val path = Files.createTempDirectory("credlock-rt").resolve("auth.json")
        Files.writeString(path, "{}")
        assertEquals(1, CredentialLock.withLock(path) { 1 })
        // A second sequential acquisition only succeeds if the first actually released.
        assertEquals(2, CredentialLock.withLock(path) { 2 })
    }

    @Test
    fun `withLock serializes two concurrent same-JVM callers`() = runBlocking {
        val path = Files.createTempDirectory("credlock-serial").resolve("auth.json")
        Files.writeString(path, "{}")
        val order = mutableListOf<String>()
        val aReleased = CompletableDeferred<Unit>()

        // A enters the lock, records, then parks holding it until we release aReleased.
        val a = launch {
            CredentialLock.withLock(path) {
                order.add("a-enter")
                aReleased.await()
                order.add("a-exit")
            }
        }
        while (order.isEmpty()) yield() // wait until A is inside the lock
        // B contends for the SAME path — it must NOT enter while A holds the lock.
        val b = launch {
            CredentialLock.withLock(path) {
                order.add("b-enter")
            }
        }
        repeat(1000) { yield() } // give B every chance to (wrongly) enter
        assertEquals(listOf("a-enter"), order) // B is genuinely blocked, not merely yielding

        aReleased.complete(Unit) // A finishes and releases; B may now proceed
        a.join()
        b.join()
        assertEquals(listOf("a-enter", "a-exit", "b-enter"), order)
    }

    @Test
    fun `withLock never touches the credential file - it locks a sibling lock file`() = runTest {
        val path = Files.createTempDirectory("credlock-sibling").resolve("auth.json")
        Files.writeString(path, """{"token":"secret"}""")
        val lockPath = path.resolveSibling("auth.json.lock")
        assertFalse(Files.exists(lockPath)) // not created until first use

        CredentialLock.withLock(path) { /* no-op */ }

        assertEquals("""{"token":"secret"}""", Files.readString(path)) // credential untouched
        assertTrue(Files.exists(lockPath)) // sibling lock file created beside it
    }

    @Test
    fun `a peer holding the lock past the budget degrades to unlocked - SH-06`() = runBlocking {
        // The live-slow-peer case: a second channel holds the sibling .lock for LONGER than the
        // budget. withLock must return within budget+slack, log the honest degrade line, and the
        // block must still have run (bounded-and-unlocked beats hung; G1's layers own the race).
        val dir = Files.createTempDirectory("credlock-degrade")
        val path = dir.resolve("auth.json")
        Files.writeString(path, "{}")
        val lockPath = dir.resolve("auth.json.lock")
        val holder = java.nio.channels.FileChannel.open(
            lockPath,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        val held = holder.lock()
        try {
            val logs = mutableListOf<String>()
            var ran = false
            val t0 = System.currentTimeMillis()
            val result = CredentialLock.withLock(path, waitMs = 600, log = logs::add) {
                ran = true
                "went-unlocked"
            }
            val took = System.currentTimeMillis() - t0
            assertEquals("went-unlocked", result)
            assertTrue(ran, "the refresh block must still run after the degrade")
            assertTrue(took in 600..5_000, "must return within budget+slack, took ${took}ms")
            assertTrue(
                logs.any { it.contains("proceeding unlocked") },
                "the degrade must be honestly logged, got $logs",
            )
        } finally {
            held.release()
            holder.close()
        }
    }

    @Test
    fun `a peer releasing inside the budget hands the lock over normally - SH-06`() = runBlocking {
        val dir = Files.createTempDirectory("credlock-handover")
        val path = dir.resolve("auth.json")
        Files.writeString(path, "{}")
        val lockPath = dir.resolve("auth.json.lock")
        val holder = java.nio.channels.FileChannel.open(
            lockPath,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        val held = holder.lock()
        val releaser = launch {
            kotlinx.coroutines.delay(300)
            held.release()
            holder.close()
        }
        val logs = mutableListOf<String>()
        val result = CredentialLock.withLock(path, waitMs = 10_000, log = logs::add) { "locked-run" }
        releaser.join()
        assertEquals("locked-run", result)
        assertTrue(logs.none { it.contains("proceeding unlocked") }, "no degrade when the peer yields: $logs")
    }
}
