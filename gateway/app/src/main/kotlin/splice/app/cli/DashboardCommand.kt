// NEW: `splice dashboard` — cold-starts the daemon if needed and opens the control panel in the
// browser. Prints the mgmt-key so the (unmodified) webui can authenticate if it asks. :app: println.
package splice.app.cli

/** The `dashboard` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). `Command.Dashboard` constructs one per invocation; the member
 *  keeps the old function's name so the diff is a receiver insertion. */
internal class DashboardCommand {

    internal fun dashboard(): Boolean {
        val port = AdminSupport.controlPort()
        if (!AdminSupport.ensureDaemon(port)) {
            println("splice: the daemon isn't running and couldn't be started.")
            return false
        }
        val url = "http://127.0.0.1:$port"
        // DR-174: the silent member of the class. An unreadable key printed NOTHING here, so the
        // dashboard prompted for a key the operator had no way to learn they already own — the
        // absent case is legitimately silent (nothing to paste yet), the unreadable one is not.
        when (val read = AdminSupport.readMgmtKey()) {
            is MgmtKeyRead.Present -> println("splice: dashboard key (paste if prompted): ${read.key}")
            is MgmtKeyRead.Unreadable -> println(
                "splice: dashboard key unreadable (${read.reason}) — fix its permissions; " +
                    "the dashboard will prompt and there is nothing to paste until you do",
            )
            is MgmtKeyRead.Absent -> Unit
        }
        if (AdminSupport.openUrl(url)) {
            println("splice: opened $url")
        } else {
            println("splice: open the dashboard at $url")
        }
        return true
    }
}
