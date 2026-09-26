// NEW: V4-276 — `splice login <claude-head> --label <name> [--discard]`, the CLI half of the labelled
// Claude logins. ClaudeLogins.login holds the four invariants; this side supplies what only the CLI
// knows about the head: the config dir its Claude Code runs over (HeadConfigDirs, the one spelling)
// and which of its sessions still run, read from Claude Code's own session registry the way `splice
// sessions` reads it (every head's config dir links its `sessions` there). A running session whose
// head splice cannot place counts as this head's: the switch is refused, never guessed.
package splice.app.cli.auth

import splice.app.head.HeadConfigDirs
import splice.client.ClaudeHead
import splice.client.ClaudeLoginResult
import splice.client.ClaudeLogins
import splice.client.HeadSessions
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Path
import java.nio.file.Paths

internal class ClaudeLoginLabel(
    private val output: TerminalOutput,
    private val logins: ClaudeLogins = ClaudeLogins(),
    private val sessionsDir: Path = Paths.get(System.getProperty("user.home"), ".claude", "sessions"),
    private val processes: ProcessEnvironment = ProcessEnvironment(),
) {
    internal fun login(headKey: String, topology: Topology, label: String?, discard: Boolean): Boolean {
        if (label == null) {
            output.line(
                "splice: $headKey signs in with Claude Code's own /login inside it; `splice login $headKey " +
                    "--label <name>` saves that login under a name, or switches to one saved before.",
            )
            return false
        }
        val head = ClaudeHead(headKey, HeadConfigDirs.of(headKey, topology.heads[headKey]?.claude?.configDir))
        return when (val result = logins.login(head, label, sessions(headKey, topology), discard)) {
            is ClaudeLoginResult.Done -> true.also { output.line("splice: ${result.said}") }
            is ClaudeLoginResult.Refused -> false.also { output.line("splice: ${result.reason}") }
            ClaudeLoginResult.Ok -> true
        }
    }

    /** The head's running sessions, read fresh from the registry at the moment of the switch. */
    internal fun sessions(headKey: String, topology: Topology): HeadSessions {
        val registry = SessionRegistry(
            sessionsDir,
            RouteOfPid { pid ->
                processes.route(pid) { port -> topology.heads.entries.firstOrNull { it.value.port == port }?.key }
            },
        )
        return live(registry.list(), headKey)
    }

    /** A registry that could not be listed is Unreadable, never "no sessions". */
    internal fun live(listing: SessionListing, headKey: String): HeadSessions = listing.error
        ?.let { HeadSessions.Unreadable(it) }
        ?: HeadSessions.Read(listing.sessions.filter { runs(it, headKey) }.map(::describe))

    private fun runs(session: SessionRecord, headKey: String): Boolean =
        session.availability != SessionAvailability.GONE &&
            (session.head == headKey || session.route == SessionRoute.Unknown)

    private fun describe(session: SessionRecord): String {
        val name = session.name?.let { "'${clean(it)}'" }
            ?: session.sessionId?.let { "session ${clean(it)}" }
            ?: "a session"
        val unplaced = if (session.route == SessionRoute.Unknown) ", whose head splice cannot tell" else ""
        return "$name, pid ${session.pid}$unplaced"
    }

    /** Registry text is Claude Code's, not ours: no control or format character reaches the terminal. */
    private fun clean(text: String): String =
        text.filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
}
