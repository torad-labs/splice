// NEW: Oct 10, 2026 — what TeamStart needs from this daemon, each read at CALL time: the command a head launches
// under, the account pin for a slot that names one, and whether a session Claude Code was started with has
// registered itself. The terminal arrives the same way, through ConsolePorts, so nothing here names a terminal
// product (TMUX.md: one contract, and the implementation is one module to delete).
package splice.app.control.mount

import splice.accounts.pool.HeadAccountPinSource
import splice.app.control.ManagedHead
import splice.core.config.InstallPaths
import splice.sessions.http.AccountPins
import splice.sessions.http.SessionArrival
import splice.sessions.http.SessionHandover
import splice.sessions.http.StartCommand
import splice.sessions.http.StartCommands
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import splice.upstream.Waiter
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** How often a start asks the registry whether its session has come up. */
// why: Claude Code writes its registration once at boot, so this only decides how soon a successful start answers.
private const val ARRIVAL_POLL_MS = 250L

/** The command a head's member starts under: the head's own wrapper command, which must be linked where
 *  `splice install` puts it, and is run by that path: a terminal resolves a bare name on its own server's PATH, which
 *  can find another wrapper of the same name or none, so it runs exactly the one checked here — a recipe naming a command that does not run is the refusal ResumeRecipeRoute
 *  already gives (409 with the fix), and a start gets the same sentence rather than a terminal that exits. */
internal class HeadStartCommands(
    private val heads: Map<String, ManagedHead>,
    private val installPaths: InstallPaths = InstallPaths(),
) : StartCommands {
    override fun forHead(head: String): StartCommand {
        val managed = heads[head]
        val command = managed?.head?.label
        return when {
            managed == null || managed.launchSpec == null || command == null ->
                StartCommand.Refused("no launchable head is keyed '$head'")
            !Files.isSymbolicLink(installPaths.binDir.resolve(command)) ->
                StartCommand.Refused("The $command command is not linked; run splice install $head.")
            else -> StartCommand.Ready(listOf(installPaths.binDir.resolve(command).toString()))
        }
    }
}

/** Pins the new session to the slot's account, through the head's own pool (the same pin POST
 *  /api/auth/{head}/switch makes with a session). False when the head has no pool or no such account, which
 *  the route reports rather than starting a member on whichever account the policy would have picked. */
internal class PoolAccountPins(private val heads: Map<String, ManagedHead>) : AccountPins {
    override fun pin(head: String, label: String, session: String): Boolean {
        val pool = heads[head]?.authSurface?.accountPool as? HeadAccountPinSource ?: return false
        return pool.pin(label, session)
    }
}

/** Whether Claude Code registered the session a start named, within the deadline the route sets. */
internal class RegistryArrival(
    private val sessions: SessionSource?,
    private val waiter: Waiter,
) : SessionArrival {
    override suspend fun arrived(session: String, seconds: Long): Boolean {
        var waited = 0L
        val deadline = seconds * 1000L
        while (true) {
            if (live(session)) return true
            if (waited >= deadline) return false
            waiter.wait(ARRIVAL_POLL_MS)
            waited += ARRIVAL_POLL_MS
        }
    }

    private fun live(session: String): Boolean =
        sessions?.read().orEmpty().any { it.sessionId == session && it.availability == SessionAvailability.LIVE }
}

/** The two waits a session's move makes (Continue on, SessionContinue), read off the same registry: that its old
 *  client exited, and that it registered again on the command it moved to. */
internal class RegistryHandover(private val sessions: SessionSource?, private val waiter: Waiter) : SessionHandover {
    /** Claude Code's own words for a turn in flight. */
    private val running = setOf("working", "busy")

    /** Claude Code drops its registry file before its process is gone, and a resume typed in that gap lands in the
     *  dying client's terminal and is lost, so the process itself has to have ended too. */
    override suspend fun left(session: String, pid: Long, seconds: Long): Boolean = within(seconds) {
        val ended = ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
        ended && sessions?.read().orEmpty().none {
            it.sessionId == session && it.process.pid == pid && it.availability != SessionAvailability.GONE
        }
    }

    override suspend fun stopped(session: String, seconds: Long): Boolean = within(seconds) {
        sessions?.read().orEmpty().none { it.sessionId == session && it.status.state in running }
    }

    override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean = within(seconds) {
        sessions?.read().orEmpty().any {
            it.sessionId == session && it.head == head && it.availability == SessionAvailability.LIVE
        }
    }

    private suspend inline fun within(seconds: Long, done: () -> Boolean): Boolean {
        val deadline = TimeUnit.SECONDS.toMillis(seconds)
        var waited = 0L
        while (!done()) {
            if (waited >= deadline) return false
            waiter.wait(ARRIVAL_POLL_MS)
            waited += ARRIVAL_POLL_MS
        }
        return true
    }
}
