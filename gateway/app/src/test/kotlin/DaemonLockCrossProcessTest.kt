// NEW (DR-162): the single-flight gate asserted from OUTSIDE the JVM that holds it.
//
// DaemonTest's `daemon lock is single-flight` cannot see the defect this pins, and that is the
// point: it asks the JVM whether the lock is held, and the JVM's answer stays true even after
// POSIX has dropped the lock underneath it. Only another process can tell. The losing acquire used
// to open and close a second descriptor for the held file, which releases every fcntl lock this
// process holds on it — so the arm named "single-flight" was itself the thing that ended
// single-flight, and it passed while doing it.
//
// python3 rather than a second JVM: it is already a build dependency (every gate leg runs it) and
// `fcntl.lockf` is the same F_SETLK primitive java.nio uses, so the two contend for real. `flock(1)`
// would NOT work — flock(2) and fcntl(3) are independent lock spaces on Linux and would agree
// vacuously.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonLock
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val PROBE_TIMEOUT_S = 30L

// Prints exactly one of "acquired" / "refused" — an exclusive non-blocking fcntl attempt.
private const val LOCK_PROBE = """
import fcntl, sys
fd = open(sys.argv[1], 'r+')
try:
    fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    print('acquired')
except OSError:
    print('refused')
"""

// Takes the lock, says so, then holds it until stdin closes — the foreign holder the third arm needs.
private const val LOCK_HOLDER = """
import fcntl, sys
fd = open(sys.argv[1], 'r+')
fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
sys.stdout.write('held\n')
sys.stdout.flush()
sys.stdin.read()
"""

private fun python3Available(): Boolean =
    runCatching { ProcessBuilder("python3", "-c", "pass").start().waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS) }
        .getOrDefault(false)

/** What a DIFFERENT process sees: "acquired" means this JVM's lock is not actually held. */
private fun foreignAttempt(file: Path): String {
    val p = ProcessBuilder("python3", "-c", LOCK_PROBE, file.toString()).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().decodeToString().trim()
    p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
    return out
}

class DaemonLockCrossProcessTest {

    @Test
    fun `a losing acquire must not release the winner's lock for other processes - DR-162`(@TempDir tmp: Path) {
        assumeTrue(python3Available(), "python3 is required to contend for the lock from another process")
        val file = tmp.resolve("daemon.lock")
        val winner = DaemonLock(file)
        assertTrue(winner.tryAcquire(), "the first acquire wins")
        try {
            assertEquals("refused", foreignAttempt(file), "sanity: the lock really is held before the loser runs")
            val loser = DaemonLock(file)
            assertFalse(loser.tryAcquire(), "a second holder in this process loses")
            assertEquals(
                "refused",
                foreignAttempt(file),
                "the losing attempt must not have handed the lock to every other process",
            )
        } finally {
            winner.close()
        }
        assertEquals("acquired", foreignAttempt(file), "and after close the lock is genuinely free")
    }

    @Test
    fun `two spellings of one path are one reservation - DR-162`(@TempDir tmp: Path) {
        assumeTrue(python3Available(), "python3 is required to contend for the lock from another process")
        java.nio.file.Files.createDirectories(tmp.resolve("sub")) // so the roundabout spelling resolves
        val direct = tmp.resolve("daemon.lock")
        val roundabout = tmp.resolve("sub").resolve("..").resolve("daemon.lock")
        val winner = DaemonLock(direct)
        assertTrue(winner.tryAcquire())
        try {
            assertFalse(DaemonLock(roundabout).tryAcquire(), "the same file spelled differently is the same lock")
            assertEquals(
                "refused",
                foreignAttempt(direct),
                "a reservation keyed on the raw spelling would have opened a descriptor and freed the lock",
            )
        } finally {
            winner.close()
        }
    }

    @Test
    fun `close hands the same-process reservation back - DR-162`(@TempDir tmp: Path) {
        val file = tmp.resolve("daemon.lock")
        val first = DaemonLock(file)
        assertTrue(first.tryAcquire())
        first.close()
        val second = DaemonLock(file)
        assertTrue(second.tryAcquire(), "the reservation must not outlive the lock it stands for")
        second.close()
    }

    @Test
    fun `losing to a FOREIGN holder still allows a later win - DR-162`(@TempDir tmp: Path) {
        assumeTrue(python3Available(), "python3 is required to hold the lock from another process")
        val file = tmp.resolve("daemon.lock")
        DaemonLock(file).use { seed -> assertTrue(seed.tryAcquire(), "create the file and free it again") }

        val holder = ProcessBuilder("python3", "-c", LOCK_HOLDER, file.toString()).start()
        try {
            assertEquals("held", holder.inputStream.bufferedReader().readLine(), "the foreign holder must be armed")
            val blocked = DaemonLock(file)
            assertFalse(blocked.tryAcquire(), "a foreign process holds it, so we lose")
        } finally {
            holder.outputStream.close() // stdin EOF — the holder exits and the lock is released
            holder.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        }
        // The reservation is only a stand-in for a lock we actually hold; losing must not keep it.
        val retry = DaemonLock(file)
        assertTrue(retry.tryAcquire(), "a lost acquire must not bar this process from ever winning")
        retry.close()
    }
}
