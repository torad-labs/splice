// NEW: desktop-antigravity. Runs `splice desktop launch` again outside the no-new-privileges bit (PrivilegeEscape) and
// stays its stand-in for Desktop: Desktop's streams are the unit's (systemd-run --pipe), this process exits with the
// session's exit code, and a TERM, INT or HUP that Desktop sends it reaches the launch inside as that same signal
// (`systemctl --user kill --kill-whom=main`), so the session ends as it would have without the escape. QUIT is relayed
// too where the Java runtime gives it up (it keeps it for itself unless started with -Xrs). The relays are in place
// before systemd-run starts and taken back once it has ended.
package splice.app.cli.desktop

import splice.core.terminal.TerminalOutput
import splice.core.util.SafeFailureText
import sun.misc.Signal
import sun.misc.SignalHandler
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs [command] (systemd-run's, starting user unit [unit]) with Desktop's streams; [systemctl] relays signals to
 *  the unit, and what goes wrong goes to [err]. */
internal class EscapedLaunch(
    private val command: List<String>,
    private val unit: String,
    private val systemctl: String,
    private val err: TerminalOutput,
) {
    /** The session's exit code, run with [inheritedEnv] laid over this process's environment; null when systemd-run
     *  could not be started, so the launch runs here after all. */
    fun run(inheritedEnv: Map<String, String>): Int? {
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment().putAll(inheritedEnv)
        builder.environment()[ESCAPED_ENV] = ESCAPED
        val previous = RELAYED.mapNotNull { name -> relay(name)?.let { name to it } }
        try {
            val run = try {
                builder.start()
            } catch (e: IOException) {
                val why = SafeFailureText.render(e)
                err.line("splice desktop launch: $SYSTEMD_RUN_PROGRAM could not be started ($why); it runs here")
                return null
            }
            return run.waitFor()
        } finally {
            previous.forEach { (name, handler) -> Signal.handle(Signal(name), handler) }
        }
    }

    /** Hands signal [name] to the unit instead of ending this process, where the Java runtime lets it go; the handler
     *  it had, or null when the runtime keeps the signal. */
    private fun relay(name: String): SignalHandler? = try {
        Signal.handle(Signal(name)) { send(name) }
    } catch (_: IllegalArgumentException) {
        // The runtime keeps this signal for itself (QUIT without -Xrs), so it is not relayed.
        null
    }

    private fun send(name: String) {
        val kill = listOf(systemctl, "--user", "kill", "--kill-whom=main", "--signal=SIG$name", unit)
        try {
            val sent = ProcessBuilder(kill).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            if (!sent.waitFor(SIGNAL_RELAY_WAIT_MS, TimeUnit.MILLISECONDS)) sent.destroyForcibly()
        } catch (e: IOException) {
            err.line("splice desktop launch: SIG$name could not be passed on to $unit: ${SafeFailureText.render(e)}")
        }
    }
}

/** The signals Desktop ends a session with, passed on to the launch inside. */
private val RELAYED = listOf("TERM", "INT", "HUP", "QUIT")

// why: systemctl kill answers from the user manager at once; one that has not in five seconds is stuck on the bus.
private const val SIGNAL_RELAY_WAIT_MS = 5_000L
