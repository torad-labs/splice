// NEW: the lock loser's bounded wait — what DaemonLock's header always promised ("the loser waits
// briefly, health-checks the winner, exits 0 LOUD") and Main never did: one tryLock, then exit.
//
// Why a single attempt loses to a daemon that is already leaving (fresh-machine e2e, 2026-09-02):
// `splice restart` spawns the new daemon the moment the old one's PORTS are free, and a port is
// free at unbind — but the old process releases the lock only after its engines' stop grace, the
// async log drain and lock.close(), up to a second later on a loaded box. The new JVM reached
// tryLock inside that window, printed "the winner serves", and exited — while the "winner" was
// the process on its way out. Nothing served, and restart reported "did not come up".
//
// So the loser now polls the lock for the old daemon's whole teardown floor (STOP_DEADLINE_MS +
// TEARDOWN_TAIL_GRACE_MS in Main.kt, 10s) — still inside the spawner's 15s health budget — and
// yields at once to a peer that ANSWERS /health, because that one is a live winner, not a
// leaving one. Thread.sleep, not delay: this runs before any coroutine scope exists, the same
// way the CLI's own port polls do.
package splice.app

/** Answers whether a daemon is serving on [port]; injected so the wait is testable without a socket. */
internal fun interface PeerProbe {
    fun serving(port: Int): Boolean
}

internal enum class LockOutcome { WON, PEER_SERVING, EXPIRED }

internal class DaemonLockWait(
    private val peer: PeerProbe = PeerProbe { port -> DaemonProbe.healthVersion(port) != null },
    private val polls: Int = LOCK_WAIT_POLLS,
    private val pollIntervalMs: Long = LOCK_POLL_INTERVAL_MS,
) {
    /** Poll [lock] until it is ours, a live peer answers on [controlPort], or the window expires. */
    fun acquire(lock: DaemonLock, controlPort: Int): LockOutcome {
        repeat(polls) {
            if (lock.tryAcquire()) return LockOutcome.WON
            if (peer.serving(controlPort)) return LockOutcome.PEER_SERVING
            Thread.sleep(pollIntervalMs)
        }
        return if (lock.tryAcquire()) LockOutcome.WON else LockOutcome.EXPIRED
    }

    fun windowMs(): Long = polls * pollIntervalMs
}

// 10s: the old daemon's cooperative cap plus its tail grace (Main.kt's STOP_DEADLINE_MS +
// TEARDOWN_TAIL_GRACE_MS) — past that the old process has halted and the lock is free, or the
// holder is something a new daemon must not race. Below the spawner's STARTUP_POLLS budget.
private const val LOCK_WAIT_POLLS = 40
private const val LOCK_POLL_INTERVAL_MS = 250L
