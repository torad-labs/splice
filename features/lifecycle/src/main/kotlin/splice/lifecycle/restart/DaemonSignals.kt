// NEW: which process holds a daemon's port, and how a signal reaches it (LAYOUT-01). The stop ladder
// in DaemonStop decides WHEN to signal; this file answers WHO and HOW. Both halves came here with the
// restart verb: the `ss`-scoped lookup from app's DaemonBoundary (only the CLI's stop ladder ever
// called it) and SignalSend from app's CliPorts (HD-22, wave 4b).
package splice.lifecycle.restart

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

/**
 * Delivers one signal to the daemon process — `destroy()` (TERM) or `destroyForcibly()` (KILL).
 *
 * The BOOLEAN is the whole reason this is a seam rather than a hardcoded call. Both returns used to
 * be discarded while the preceding line already told the operator the signal had been sent, so an
 * undelivered TERM read as "the daemon did not stop" with no hint that nothing was ever signalled.
 * False means not delivered, and the escalation ladder is required to say so.
 */
internal fun interface SignalSend {
    operator fun invoke(handle: ProcessHandle): Boolean
}

/** The splice daemon holding a port, found through `ss` and scoped by its command line so a signal
 *  never reaches another process on the box. [errors] hears when the lookup itself fails. */
internal class DaemonPortOwner(private val errors: TerminalOutput) {

    fun daemonOnPort(port: Int): ProcessHandle? = pidsOnPort(port)
        .firstNotNullOfOrNull { pid ->
            ProcessHandle.of(pid).orElse(null)?.takeIf { ph ->
                val cmd = ph.info().commandLine().orElse("")
                cmd.contains("daemon") && (cmd.contains("splice.jar") || cmd.contains("app-all.jar"))
            }
        }

    fun pidsOnPort(port: Int): List<Long> = Cancellables.runCatchingCancellable {
        ProcessBuilder("ss", "-ltnpH", "( sport = :$port )").redirectErrorStream(true).start()
            .inputStream.bufferedReader().use { it.readText() }
            .let { Regex("pid=(\\d+)").findAll(it).map { m -> m.groupValues[1].toLong() }.toList() }
    }.onFailure {
        // An empty list and "ss is missing / refused" read identically to the stop ladder, which is
        // the 2026-07-18 shape exactly: say which one happened.
        errors.line("[daemon] port->pid lookup via ss failed (${SafeFailureText.render(it)})")
    }.getOrDefault(emptyList())
}
