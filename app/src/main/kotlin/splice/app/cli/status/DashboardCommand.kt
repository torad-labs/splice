// NEW: `splice dashboard` — cold-starts the daemon if needed and opens the control panel in the
// browser. Prints the mgmt-key so the webui can authenticate if it asks — to a terminal only (v0.4.0
// review): a model running this from a session's shell tool reads stdout into its transcript, which
// is how the key retired on 2026-09-23 reached eight of them. :app: println.
// 2026-09-24: it opens the console UNLOCKED, so the operator never pastes the key. The browser is
// sent to a 0600 redirect page in the state dir that carries the key in the address's FRAGMENT, which
// never reaches the daemon or a log, and the page's path is all that argv carries. Jupyter's
// use_redirect_file, for the reason its docs give: a key on a command line or in an HTTP answer is
// readable by every local process, and loopback is shared by every account on the box.
package splice.app.cli.status

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.core.config.StatePaths
import splice.core.util.SecureFile
import splice.daemonclient.MgmtKeyRead
import splice.terminal.ConsolePresence
import java.net.URLEncoder
import java.nio.file.Path

/** The redirect page's name in the state dir; rewritten on every run, never read back. */
internal const val LAUNCH_PAGE = "dashboard-open.html"

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
        val read = AdminSupport.readMgmtKey()
        keyLine(read)?.let(::println)
        // With no key to hand over, the bare address: the console's own gate says what is wrong.
        val target = (read as? MgmtKeyRead.Present)
            ?.let { launchPage(StatePaths().stateDir, url, it.key).toUri().toString() }
            ?: url
        if (AdminSupport.openUrl(target)) {
            println("splice: opened $url")
        } else {
            println("splice: open the dashboard at $url")
        }
        return true
    }

    /** Writes the page the browser is sent to: a redirect to [url] with the key in the fragment,
     *  owner-only from the instant it exists (the law `mgmt-key` itself is written under). */
    internal fun launchPage(stateDir: Path, url: String, key: String): Path {
        val target = "$url/#k=${URLEncoder.encode(key, Charsets.UTF_8)}"
        val page = stateDir.resolve(LAUNCH_PAGE)
        SecureFile.writeAtomic0600(
            page,
            "<!doctype html><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">" +
                "<meta http-equiv=\"refresh\" content=\"0;url=$target\"><title>splice</title>" +
                "<a href=\"$target\">open the splice console</a>\n",
        )
        return page
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
