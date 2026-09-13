// NEW (v0.4.0, FEATURES.md §4): `splice sessions` — the Claude Code sessions registered in
// ~/.claude/sessions, joined to the splice head each one talks to, with the copyable SendMessage
// address per live session. Read-only: the registry is Claude Code's, and no socket is touched.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.sessions.HeadOfPid
import splice.core.sessions.ProcessEnvironment
import splice.core.sessions.SessionAvailability
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry
import splice.core.util.EnvReader
import splice.core.util.WallClock
import java.nio.file.Paths

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val CWD_MAX = 48

internal class SessionsCommand {

    internal fun sessions(
        envReader: EnvReader = EnvReader(System::getenv),
        registry: SessionRegistry = defaultRegistry(envReader),
        now: WallClock = WallClock { System.currentTimeMillis() },
    ): Boolean {
        val home = Paths.get(System.getProperty("user.home")).toString()
        println("${BOLD}splice sessions$RESET $DIM— Claude Code sessions registered in ~/.claude/sessions$RESET")
        println()
        val rows = registry.read()
        if (rows.isEmpty()) println("  $DIM–  no registered sessions$RESET")
        rows.forEach { s -> printRow(s, home, now()) }
        println()
        println("  ${DIM}gone = the process exited · stale = alive, no registry update for 30 min · $RESET")
        println("  ${DIM}headless `claude -p` runs never register here$RESET")
        return true
    }

    private fun printRow(s: SessionRecord, home: String, now: Long) {
        val name = s.name ?: s.pid?.let { "pid $it" } ?: "?"
        val head = s.head ?: "unknown head"
        val cwd = shortCwd(s.cwd.orEmpty().replaceFirst(home, "~"))
        val age = s.updatedAt?.let { ago(now - it) } ?: "never"
        val availability = s.availability.name.lowercase()
        println(
            "  ${glyph(s.availability)} ${BOLD}$name$RESET  $CYAN$head$RESET  ${s.status ?: "-"}  " +
                "$availability  $DIM$age · $cwd$RESET",
        )
        sendLine(s)?.let { println(it) }
    }

    private fun glyph(availability: SessionAvailability): String = when (availability) {
        SessionAvailability.LIVE -> "$GREEN●$RESET"
        SessionAvailability.STALE -> "$YELLOW●$RESET"
        SessionAvailability.GONE -> "$DIM○$RESET"
    }

    /** The copyable SendMessage target: the name when the session has one, else its socket address. */
    private fun sendLine(s: SessionRecord): String? {
        if (s.availability == SessionAvailability.GONE) return null
        val to = s.name ?: s.address ?: return null
        val socket = s.address?.let { "  $DIM# $it$RESET" }.orEmpty()
        return "      ${DIM}send:$RESET SendMessage(to=\"$to\")$socket"
    }

    private fun shortCwd(cwd: String): String = if (cwd.length > CWD_MAX) "…" + cwd.takeLast(CWD_MAX) else cwd

    private fun ago(ms: Long): String {
        val minutes = ms / MS_PER_MINUTE
        return when {
            minutes < 1 -> "just now"
            minutes < MINUTES_PER_HOUR -> "${minutes}m ago"
            else -> "${minutes / MINUTES_PER_HOUR}h ago"
        }
    }

    private fun defaultRegistry(envReader: EnvReader): SessionRegistry {
        val heads = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(envReader)).heads
        val environment = ProcessEnvironment()
        return SessionRegistry(
            Paths.get(System.getProperty("user.home"), ".claude", "sessions"),
            HeadOfPid { pid ->
                val port = environment.spliceHeadPort(pid)
                heads.entries.firstOrNull { it.value.port == port }?.key
            },
        )
    }
}
