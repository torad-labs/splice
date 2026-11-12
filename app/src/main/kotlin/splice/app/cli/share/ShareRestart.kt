// NEW: LAN share (docs/specs/lan-share.md). The restart `splice share` and `splice remote` take once they changed
// splice.toml, which the daemon reads only at its start: the share listener opens with it, and the commands another
// machine serves are the ones it read then. A running daemon restarts once; one that is down follows the file when it
// starts, and is said to.
package splice.app.cli.share

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.core.terminal.CYAN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.EnvReader

internal class ShareRestart(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val env: EnvReader,
    private val verb: String,
) {
    /** Whether the daemon of [topology] answers now. */
    fun up(topology: Topology): Boolean = AdminSupport.daemonUp(AdminSupport.controlPort(topology, env))

    /** Whether the daemon follows [topology], now or at its start; false, said, when its restart failed. */
    fun followed(topology: Topology): Boolean {
        if (!up(topology)) {
            out.line("  splice is not running here; it follows this when it starts (${CYAN}splice start$RESET)")
            return true
        }
        if (LifecycleWiring.restart()) return true
        err.line("$verb: the restart failed; run ${CYAN}splice restart$RESET")
        return false
    }
}
