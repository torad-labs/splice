// NEW: the lock loser waits out a leaving daemon and yields to a serving one (see DaemonLockWait).
// The holder is a second DaemonLock on the same path: DR-162 refuses a second acquire in-process
// without touching the file, so "held" here is exactly what the poll sees when another process
// holds it — false until the holder closes.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonLock
import splice.app.DaemonLockWait
import splice.app.LockOutcome
import splice.app.PeerProbe
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DaemonLockWaitTest {

    private val nobodyServes = PeerProbe { false }

    @Test
    fun `a lock released mid-window is won, not conceded`(@TempDir dir: Path) {
        val path = dir.resolve("daemon.lock")
        val leaving = DaemonLock(path)
        assertTrue(leaving.tryAcquire(), "the holder wins first")
        val probes = AtomicInteger()
        // The "old daemon" lets go after the second poll has looked.
        val peer = PeerProbe {
            if (probes.incrementAndGet() == 2) leaving.close()
            false
        }
        val wait = DaemonLockWait(peer, polls = 20, pollIntervalMs = 5)
        val ours = DaemonLock(path)
        try {
            assertEquals(LockOutcome.WON, wait.acquire(ours, 0))
            assertTrue(probes.get() >= 2, "the loser waited through the holder's release, saw ${probes.get()} polls")
        } finally {
            ours.close()
        }
    }

    @Test
    fun `a peer that answers health is a live winner — yield at once`(@TempDir dir: Path) {
        val path = dir.resolve("daemon.lock")
        val winner = DaemonLock(path)
        assertTrue(winner.tryAcquire())
        val probes = AtomicInteger()
        val wait = DaemonLockWait(PeerProbe { probes.incrementAndGet() > 0 }, polls = 20, pollIntervalMs = 5)
        try {
            assertEquals(LockOutcome.PEER_SERVING, wait.acquire(DaemonLock(path), 0))
            assertEquals(1, probes.get(), "one health probe decides it")
        } finally {
            winner.close()
        }
    }

    @Test
    fun `a holder that never serves and never leaves expires the window`(@TempDir dir: Path) {
        val path = dir.resolve("daemon.lock")
        val wedged = DaemonLock(path)
        assertTrue(wedged.tryAcquire())
        val wait = DaemonLockWait(nobodyServes, polls = 4, pollIntervalMs = 5)
        try {
            assertEquals(LockOutcome.EXPIRED, wait.acquire(DaemonLock(path), 0))
            assertEquals(20L, wait.windowMs())
        } finally {
            wedged.close()
        }
    }

    @Test
    fun `a free lock is won on the first look`(@TempDir dir: Path) {
        val ours = DaemonLock(dir.resolve("daemon.lock"))
        val neverProbed = PeerProbe { error("never probed when the lock is free") }
        val wait = DaemonLockWait(neverProbed, polls = 4, pollIntervalMs = 5)
        try {
            assertEquals(LockOutcome.WON, wait.acquire(ours, 0))
        } finally {
            ours.close()
        }
    }
}
