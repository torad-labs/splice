// NEW: `splice dashboard` — cold-starts the daemon if needed and opens the control panel in the
// browser. Prints the mgmt-key so the (unmodified) webui can authenticate if it asks — to a terminal
// only (v0.4.0 review): a model running this from a session's shell tool reads stdout into its
// transcript, which is how the key retired on 2026-09-23 reached eight of them. :app: println.
package splice.app.cli.status

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.daemonclient.MgmtKeyRead
import splice.terminal.ConsolePresence

/** The `dashboard` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). `Command.Dashboard` constructs one per invocation; the member
 *  keeps the old function's name so the diff is a receiver insertion. */
internal class DashboardCommand(
    private val terminal: ConsolePresence = ConsolePresence { System.console() != null },
) {

    internal fun dashboard(): Boolean {
        val port = AdminSupport.controlPort()
        if (!LifecycleWiring.ensureDaemon(port)) {
            println("splice: the daemon isn't running and couldn't be started.")
            return false
        }
        val url = "http://127.0.0.1:$port"
        keyLine(AdminSupport.readMgmtKey())?.let(::println)
        if (AdminSupport.openUrl(url)) {
            println("splice: opened $url")
        } else {
            println("splice: open the dashboard at $url")
        }
        return true
    }

    /** What this run says about the key the dashboard may prompt for. DR-174: the silent member of the
     *  class. An unreadable key printed NOTHING here, so the dashboard prompted for a key the operator
     *  had no way to learn they already own — the absent case is legitimately silent (nothing to paste
     *  yet), the unreadable one is not. The key itself goes to a terminal and nowhere else. */
    internal fun keyLine(read: MgmtKeyRead): String? = when (read) {
        is MgmtKeyRead.Present -> if (terminal()) {
            "splice: dashboard key (paste if prompted): ${read.key}"
        } else {
            "splice: dashboard key not printed — this output is not a terminal, and a transcript or log " +
                "would keep it; run `splice dashboard` in a terminal to see it"
        }
        is MgmtKeyRead.Unreadable ->
            "splice: dashboard key unreadable (${read.reason}) — fix its permissions; " +
                "the dashboard will prompt and there is nothing to paste until you do"
        is MgmtKeyRead.Absent -> null
    }
}
