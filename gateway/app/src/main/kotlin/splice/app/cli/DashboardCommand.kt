// NEW: `splice dashboard` — cold-starts the daemon if needed and opens the control panel in the
// browser. Prints the mgmt-key so the (unmodified) webui can authenticate if it asks. :app: println.
package splice.app.cli

public object DashboardCommand {

    public fun dashboard() {
        val port = AdminSupport.controlPort()
        if (!AdminSupport.ensureDaemon(port)) {
            println("splice: the daemon isn't running and couldn't be started.")
            return
        }
        val url = "http://127.0.0.1:$port"
        AdminSupport.mgmtKey()?.let {
            println("splice: dashboard key (paste if prompted): $it")
        }
        if (AdminSupport.openUrl(url)) {
            println("splice: opened $url")
        } else {
            println("splice: open the dashboard at $url")
        }
    }
}
