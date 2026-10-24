// NEW: LAN share, phase 2 (docs/specs/lan-share.md). The restart `splice remote` takes once it changed splice.toml's
// heads, kept apart from the import it follows (RemoteImport).
package splice.app.cli.share

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.configuration.add.DaemonRestart
import splice.core.terminal.CYAN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

/** `splice remote`'s restart once an import, a sync or a removal changed splice.toml's heads, which a daemon reads only
 *  at start: a running daemon restarts once, and one that is down follows the file at its next start. The console's
 *  Sharing page passes its own instead, which marks the restart due and takes the console's restart. */
internal class CliRestart(private val env: EnvReader, private val err: TerminalOutput) : DaemonRestart {
    override fun invoke(): Boolean {
        if (!AdminSupport.daemonUp(AdminSupport.controlPort(env)) || LifecycleWiring.restart()) return true
        err.line("$REMOTE_VERB: the restart failed; run ${CYAN}splice restart$RESET")
        return false
    }
}
