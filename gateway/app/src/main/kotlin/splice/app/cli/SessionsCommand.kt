// NEW: v0.4.0 FEATURES.md §4 — `splice sessions` — the Claude Code sessions registered in
// ~/.claude/sessions, joined to the splice head each one talks to (launches splice made, by the
// SPLICE=1 marker), with the copyable SendMessage address per LIVE session. Read-only: the registry
// is Claude Code's, no socket is touched, and the topology is only read — never materialized; when
// it cannot be read every head is "unknown". Registry text is untrusted: every string it carries
// (name, status, cwd, head, socket) goes through ONE terminal-safe renderer that drops control and
// format characters (C0, C1, DEL, Unicode Cc/Cf/Zl/Zp), and whatever lands inside the SendMessage
// syntax — name or socket — is backslash/quote-escaped so the printed command stays valid.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.sessions.HeadOfPid
import splice.core.sessions.ProcessEnvironment
import splice.core.sessions.SessionAvailability
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry
import splice.core.topology.HeadConfig
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val CWD_MAX = 48
private val UNPRINTABLE: Set<Int> = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    Character.UNASSIGNED.toInt(),
    Character.PRIVATE_USE.toInt(),
    Character.SURROGATE.toInt(),
)

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
        val name = shownName(s) ?: s.pid?.let { "pid $it" } ?: "?"
        val head = clean(s.head ?: "unknown head")
        val cwd = shortCwd(clean(s.cwd.orEmpty()).replaceFirst(home, "~"))
        val age = s.updatedAt?.let { ago(now - it) } ?: "never"
        val availability = s.availability.name.lowercase()
        println(
            "  ${glyph(s.availability)} ${BOLD}$name$RESET  $CYAN$head$RESET  ${clean(s.status ?: "-")}  " +
                "$availability  $DIM$age · $cwd$RESET",
        )
        sendLine(s)?.let { println(it) }
    }

    private fun glyph(availability: SessionAvailability): String = when (availability) {
        SessionAvailability.LIVE -> "$GREEN●$RESET"
        SessionAvailability.STALE -> "$YELLOW●$RESET"
        SessionAvailability.GONE -> "$DIM○$RESET"
    }

    /** The copyable SendMessage target, LIVE sessions only (a stale one may never answer): the name
     *  when the session has a non-blank one, else its socket address. */
    private fun sendLine(s: SessionRecord): String? {
        if (s.availability != SessionAvailability.LIVE) return null
        val socket = s.address?.let(::clean)?.takeIf { it.isNotBlank() }
        val to = (shownName(s) ?: socket)?.let(::quoted) ?: return null
        val comment = socket?.let { "  $DIM# $it$RESET" }.orEmpty()
        return "      ${DIM}send:$RESET SendMessage(to=\"$to\")$comment"
    }

    private fun shownName(s: SessionRecord): String? = s.name?.let(::clean)?.takeIf { it.isNotBlank() }

    /** Registry text is Claude Code's, not ours: no control, format or unprintable character (C0,
     *  C1, DEL, and the Unicode Cc/Cf/Zl/Zp classes) reaches the terminal, wherever it is printed. */
    private fun clean(text: String): String = text.filter { Character.getType(it) !in UNPRINTABLE }

    private fun quoted(name: String): String = name.replace("\\", "\\\\").replace("\"", "\\\"")

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
        val heads = readHeads(envReader)
        val environment = ProcessEnvironment()
        return SessionRegistry(
            Paths.get(System.getProperty("user.home"), ".claude", "sessions"),
            HeadOfPid { pid ->
                val port = environment.spliceHeadPort(pid)
                heads.entries.firstOrNull { it.value.port == port }?.key
            },
        )
    }

    /** Read-only: an absent or malformed topology means every head reads "unknown"; nothing is
     *  written (no starter file) and the listing itself never fails on it. */
    private fun readHeads(envReader: EnvReader): Map<String, HeadConfig> {
        val path = TopologyLoader.configPath(envReader)
        return try {
            TopologyLoader.parse(Files.readString(path)).heads
        } catch (unreadable: IOException) {
            unattributed(SafeFailureText.render(unreadable))
        } catch (ignored: IllegalArgumentException) {
            unattributed("malformed topology")
        } catch (ignored: IllegalStateException) {
            unattributed("malformed topology")
        }
    }

    private fun unattributed(why: String): Map<String, HeadConfig> {
        System.err.println("splice sessions: topology not readable ($why) — heads shown as unknown")
        return emptyMap()
    }
}
