// NEW: v0.4.0 FEATURES.md §4 — `splice sessions` — the Claude Code sessions registered in
// ~/.claude/sessions, joined to the splice head each one talks to (launches splice made, by the
// SPLICE=1 marker), with the copyable SendMessage address per LIVE session. Read-only: the registry
// is Claude Code's, no socket is touched, and the topology is only read — never materialized; when
// it cannot be read no splice launch is placed on a head ("unknown head"), while a session without
// SPLICE=1 is still "direct" (V4-293). Registry text is untrusted: every string it carries
// (name, status, cwd, head, socket) goes through ONE terminal-safe renderer that drops control and
// format characters (C0, C1, DEL, Unicode Cc/Cf/Zl/Zp), and whatever lands inside the SendMessage
// syntax — name or socket — is backslash/quote-escaped so the printed command stays valid.
// A sessions slice since LAYOUT-01, beside the registry it reads; the lines leave through TerminalOutput.
package splice.sessions.list

import splice.core.config.UserHome
import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.topology.HeadConfig
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.topology.TopologyLoader
import java.io.IOException
import java.nio.file.Files

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

/** `splice sessions`. [output] is the listing, [errors] the one diagnostic: an unreadable topology. */
public class SessionsCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val accounts: SessionAccounts = SessionAccounts { emptyMap() },
) {

    public fun sessions(
        envReader: EnvReader,
        registry: SessionRegistry = defaultRegistry(envReader),
        now: WallClock = WallClock { System.currentTimeMillis() },
    ): Boolean {
        val home = UserHome.dir(envReader).toString()
        output.line("${BOLD}splice sessions$RESET$DIM: Claude Code sessions registered in ~/.claude/sessions$RESET")
        output.line("")
        val listing = registry.list()
        val rows = listing.sessions
        listing.error?.let { output.line("  $RED✗$RESET the registry could not be listed: ${clean(it)}") }
        if (rows.isEmpty() && listing.error == null) output.line("  $DIM–  no registered sessions$RESET")
        val byId = if (rows.isEmpty()) emptyMap<SessionAccountKey, SessionAccountLine>() else accounts.read(envReader)
        rows.forEach { s ->
            printRow(s, home, now(), s.sessionId?.let { byId[SessionAccountKeys.of(s.head, it)] })
        }
        output.line("")
        output.line("  ${DIM}gone = the process exited · stale = alive, no registry update for 30 min · $RESET")
        output.line("  ${DIM}headless `claude -p` runs never register here$RESET")
        return listing.error == null
    }

    private fun printRow(s: SessionRecord, home: String, now: Long, account: SessionAccountLine?) {
        val name = shownName(s) ?: s.process.pid?.let { "pid $it" } ?: "?"
        val head = headLabel(s.route) + accountText(account)
        val cwd = shortCwd(clean(s.process.cwd.orEmpty()).replaceFirst(home, "~"))
        val age = s.process.updatedAt?.let { ago(now - it) } ?: "never"
        val availability = s.availability.name.lowercase()
        output.line(
            "  ${glyph(s.availability)} ${BOLD}$name$RESET  $CYAN$head$RESET  ${clean(s.status.state ?: "-")}  " +
                "$availability  $DIM$age · $cwd$RESET",
        )
        sendLine(s)?.let { output.line(it) }
    }

    /** " on work" for the account a session is on, then " (pinned to work)" when it is under a pin. The pin
     *  is named apart because a pin can name an account the session is not on yet. */
    private fun accountText(line: SessionAccountLine?): String {
        val on = line?.account?.let { " on ${clean(it)}" }.orEmpty()
        val pin = line?.pin?.let { " (pinned to ${clean(it)})" }.orEmpty()
        return on + pin
    }

    /** V4-293: a session that never went through splice is "direct", not a routing fault. */
    private fun headLabel(route: SessionRoute): String = when (route) {
        is SessionRoute.Head -> clean(route.key)
        SessionRoute.Direct -> "direct"
        SessionRoute.Unknown -> "unknown head"
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
     *  C1, DEL, and the Unicode Cc/Cf/Zl/Zp classes) reaches the terminal, wherever it is printed. Read
     *  by CODE POINT (V4-324): an emoji is two UTF-16 units and one printable character, and filtering
     *  units dropped both halves of it, so `build 🚀` was offered as a SendMessage to `build `. Only a
     *  surrogate standing alone is still SURROGATE here, and it goes. */
    private fun clean(text: String): String = buildString {
        text.codePoints().filter { Character.getType(it) !in UNPRINTABLE }.forEach { appendCodePoint(it) }
    }

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
            UserHome.claudeDir(envReader).resolve("sessions"),
            RouteOfPid { pid ->
                environment.route(pid) { port -> heads.entries.firstOrNull { it.value.port == port }?.key }
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
        errors.line("splice sessions: topology not readable ($why); heads shown as unknown")
        return emptyMap()
    }
}
