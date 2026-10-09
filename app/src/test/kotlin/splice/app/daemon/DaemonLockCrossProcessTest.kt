// NEW (DR-162): the single-flight gate asserted from OUTSIDE the JVM that holds it.
//
// DaemonTest's `daemon lock is single-flight` cannot see the defect this pins, and that is the
// point: it asks the JVM whether the lock is held, and the JVM's answer stays true even after
// POSIX has dropped the lock underneath it. Only another process can tell. The losing acquire used
// to open and close a second descriptor for the held file, which releases every fcntl lock this
// process holds on it — so the arm named "single-flight" was itself the thing that ended
// single-flight, and it passed while doing it.
//
// The other process is a second JVM started from this test's own classpath (ForeignLockProcess), so the
// test needs nothing installed beyond the JDK that runs it. java.nio's FileLock is the same F_SETLK
// primitive DaemonLock takes, so the two contend for real. `flock(1)` would NOT work — flock(2) and
// fcntl(3) are independent lock spaces on Linux and would agree vacuously.
package splice.app.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

private const val PROBE_TIMEOUT_S = 30L

private const val PROBE = "probe"
private const val HOLD = "hold"
private val VERDICTS = setOf("acquired", "refused", "held")

/** The other process: one exclusive non-blocking attempt on the file named in args[1]. [PROBE] prints
 *  "acquired" or "refused" and exits; [HOLD] prints "held" once it has the lock and keeps it until its stdin
 *  closes — the foreign holder the last arm needs. */
internal object ForeignLockProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[1]), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            println(
                when {
                    lock == null -> "refused"
                    args[0] == PROBE -> "acquired"
                    else -> "held"
                },
            )
            System.out.flush()
            if (lock != null && args[0] == HOLD) while (System.`in`.read() >= 0) Thread.onSpinWait()
        }
    }
}

private fun foreignProcess(mode: String, file: Path): Process {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    return ProcessBuilder(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        ForeignLockProcess::class.java.name,
        mode,
        file.toString(),
    ).redirectErrorStream(true).start()
}

/** What a DIFFERENT process sees: "acquired" means this JVM's lock is not actually held. */
private fun foreignAttempt(file: Path): String? {
    val p = foreignProcess(PROBE, file)
    val answer = answerOf(p)
    p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
    return answer
}

/** The child's verdict line. A JVM may print launcher notices (JAVA_TOOL_OPTIONS) on the merged stream first. */
private fun answerOf(child: Process): String? =
    child.inputStream.bufferedReader().let { reader -> generateSequence(reader::readLine) }
        .map(String::trim)
        .firstOrNull { it in VERDICTS }

class DaemonLockCrossProcessTest {

    @Test
    fun `a losing acquire must not release the winner's lock for other processes - DR-162`(@TempDir tmp: Path) {
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
        val file = tmp.resolve("daemon.lock")
        DaemonLock(file).use { seed -> assertTrue(seed.tryAcquire(), "create the file and free it again") }

        val holder = foreignProcess(HOLD, file)
        try {
            assertEquals("held", answerOf(holder), "the foreign holder must be armed")
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
