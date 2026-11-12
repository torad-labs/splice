// NEW: #523 — how a daemon started beside a running one takes over from it.
//
// A restart used to stop the running daemon and then start the next, so every answer in progress was cut. Under the
// launcher's supervisor (splice-launch, supervise_daemon) the next daemon starts while the running one still serves,
// on the same sockets, with SPLICE_SUCCEEDS set. It asks the running one to hand over (POST /api/daemon/retire),
// which answers at once, stops taking requests, writes its books and releases the daemon lock; this start has been
// waiting on that lock and boots from there. The running daemon then only finishes the answers it had begun.
//
// A running daemon that does not take the request (an older build, or one with no control server yet) is left alone,
// and this start goes the way any second daemon goes: it sees a live peer and exits. The supervisor then stops both,
// and the unit starts one daemon again, which is the restart there always was.
package splice.app.daemon

import splice.core.SHIM_VERSION
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import java.util.concurrent.atomic.AtomicLong

/** Set by the launcher on a daemon it starts beside a running one: that daemon's pid. Empty on the first. */
internal const val SPLICE_SUCCEEDS: String = "SPLICE_SUCCEEDS"

/** Set by the launcher on every daemon it supervises, to the launcher's own marker (shim-N): this process can be
 *  handed over to and from, by a launcher of that build. */
internal const val SPLICE_GENERATIONS: String = "SPLICE_GENERATIONS"

// why: the retire route's yes. The running daemon answers 202 before it starts handing over, and any other status
// (an older build's 404, a daemon with no launcher's 503) means it stays serving.
private const val HTTP_ACCEPTED = 202
private const val NEVER_ASKED = Long.MIN_VALUE

// A next daemon answers within seconds of the launcher's signal, or not at all.
private const val REASK_NS = 60_000_000_000L

/** Asks the daemon on a control port to hand over, and answers its HTTP status: null when nothing answered or
 *  there is no management key to ask with. */
internal fun interface HandOverAsk {
    fun ask(controlPort: Int): Int?
}

/** The launcher supervising this process, if one does, so there can be a next daemon for it to hand over to. */
internal class Generations(private val env: EnvReader = EnvReader(System::getenv)) {
    /** The supervising launcher's marker, as /health names it; null under none. */
    fun launcher(): String? = env(SPLICE_GENERATIONS)?.takeIf { it.isNotBlank() }

    /** Whether that launcher is this build's own. A launcher from an earlier build may run a daemon of this one
     *  after an upgrade; this daemon then neither hands over nor asks it for a next one, so its next restart is
     *  the unit's, which starts the installed launcher. */
    fun supervised(): Boolean = launcher() == SHIM_VERSION
}

/** #523: the launcher's shell that started this daemon, and the one thing a daemon asks of it: the next daemon.
 *  Built once, as the daemon starts, so [pid] is the launcher and not whatever adopted this process had the
 *  launcher died since. */
internal class Launcher(private val generations: Generations = Generations()) {
    private val pid: Long? = parent()
    private val lastAsk = AtomicLong(NEVER_ASKED)

    val supervises: Boolean get() = generations.supervised()

    /** Sends the launcher the signal `splice restart` sends it through systemd (SIGHUP), and says whether it was
     *  sent. One signal starts one daemon, so a second ask inside [REASK_NS] answers yes without sending: two
     *  daemons started side by side would both ask this one to hand over. Past that window the first ask is taken
     *  to have come to nothing, and the launcher is asked again. */
    fun startNext(): Boolean {
        val launcher = pid?.takeIf { supervises && it == parent() } ?: return false
        val now = System.nanoTime()
        val last = lastAsk.get()
        if (last != NEVER_ASKED && now - last < REASK_NS) return true
        lastAsk.set(now)
        return Cancellables.runCatchingCancellable {
            ProcessBuilder("kill", "-HUP", launcher.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
    }

    private fun parent(): Long? = ProcessHandle.current().parent().map { it.pid() }.orElse(null)
}

internal class Succession(
    private val env: EnvReader,
    private val lockWait: DaemonLockWait,
    private val err: TerminalOutput,
    private val ask: HandOverAsk = HandOverAsk { port ->
        (MgmtKeyFile().read(env) as? MgmtKeyRead.Present)?.let { read ->
            ControlPlaneClient.statusOf("http://127.0.0.1:$port/api/daemon/retire", "POST", read.key)
        }
    },
) {
    /** The daemon lock, taken the way this start was asked to take it: a daemon started to succeed another asks
     *  that one to hand over and waits for the lock alone, since the peer that still answers /health is the one
     *  leaving. Every other start waits as it always has ([DaemonLockWait.acquire]). */
    fun acquire(lock: DaemonLock, controlPort: Int): LockOutcome = when {
        env(SPLICE_SUCCEEDS).isNullOrBlank() -> lockWait.acquire(lock, controlPort)
        // The daemon this one was started beside is already gone: there is nothing to ask.
        lock.tryAcquire() -> LockOutcome.WON
        else -> succeed(lock, controlPort)
    }

    private fun succeed(lock: DaemonLock, controlPort: Int): LockOutcome {
        val status = ask.ask(controlPort)
        if (status == HTTP_ACCEPTED) return lockWait.awaitHandOver(lock)
        err.line(
            "[daemon] the daemon on :$controlPort did not take the hand-over (${status ?: "no answer"}); " +
                "this start leaves it serving",
        )
        return lockWait.acquire(lock, controlPort)
    }
}
