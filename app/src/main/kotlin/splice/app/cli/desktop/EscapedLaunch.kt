// NEW: desktop-antigravity. Runs `splice desktop launch` again outside the no-new-privileges bit (PrivilegeEscape) and
// stays its stand-in for Desktop: Desktop's streams are the unit's (systemd-run --pipe), this process exits with the
// session's exit code, and a TERM, INT or HUP that Desktop sends it reaches the launch inside as that same signal
// (`systemctl --user kill --kill-whom=main`), so the session ends as it would have without the escape. QUIT is relayed
// too where the Java runtime gives it up (it keeps it for itself unless started with -Xrs).
package splice.app.cli.desktop

import splice.core.terminal.TerminalOutput
import splice.core.util.SafeFailureText
import sun.misc.Signal
import java.io.IOException

/** Runs [command] (systemd-run's, starting user unit [unit]) with Desktop's streams; [systemctl] relays signals to
 *  the unit, and what goes wrong goes to [err]. */
internal class EscapedLaunch(
    private val command: List<String>,
    private val unit: String,
    private val systemctl: String,
    private val err: TerminalOutput,
) {
    /** The session's exit code, or null when systemd-run could not be started, so the launch runs here after all. */
    fun run(): Int? {
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment()[ESCAPED_ENV] = ESCAPED
        val run = try {
            builder.start()
        } catch (e: IOException) {
            val why = SafeFailureText.render(e)
            err.line("splice desktop launch: $SYSTEMD_RUN could not be started ($why); the session runs here")
            return null
        }
        RELAYED.forEach(::relay)
        return run.waitFor()
    }

    /** Hands signal [name] to the unit instead of ending this process, where the Java runtime lets it go. */
    private fun relay(name: String) {
        try {
            Signal.handle(Signal(name)) { send(name) }
        } catch (_: IllegalArgumentException) {
            // The runtime keeps this signal for itself (QUIT without -Xrs), so it is not relayed.
        }
    }

    private fun send(name: String) {
        val kill = listOf(systemctl, "--user", "kill", "--kill-whom=main", "--signal=SIG$name", unit)
        try {
            ProcessBuilder(kill).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        } catch (e: IOException) {
            err.line("splice desktop launch: SIG$name could not be passed on to $unit: ${SafeFailureText.render(e)}")
        }
    }
}

/** The signals Desktop ends a session with, passed on to the launch inside. */
private val RELAYED = listOf("TERM", "INT", "HUP", "QUIT")
