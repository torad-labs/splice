// NEW: Oct 10, 2026 — `splice console`: starts the daemon if it isn't running and opens the console unlocked, the way
// the removed `splice dashboard` did (2026-09-24). The browser is sent to a 0600 redirect page in the state folder that
// carries the management key in the address's FRAGMENT, which never reaches the daemon or a log, and only the page's
// path is on the command line: a key on a command line or in an HTTP answer is readable by every local process
// (Jupyter's use_redirect_file, for the same reason). The key itself is never printed.
package splice.app.cli.status

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.SecureFile
import splice.daemonclient.MgmtKeyRead
import splice.oauth.BrowserOpener
import splice.oauth.SystemBrowserOpener
import java.net.URLEncoder
import java.nio.file.Path

/** The redirect page's name in the state folder; rewritten on every run, never read back. */
internal const val CONSOLE_LAUNCH_PAGE = "console-open.html"

internal class ConsoleCommand(
    private val browser: BrowserOpener = SystemBrowserOpener(TerminalOutput { println(it) }),
) {

    internal fun open(): Boolean {
        val port = AdminSupport.controlPort()
        if (!LifecycleWiring.ensureDaemon(port)) {
            println("splice: the daemon isn't running and couldn't be started.")
            return false
        }
        val url = "http://127.0.0.1:$port"
        val target = when (val read = AdminSupport.readMgmtKey()) {
            is MgmtKeyRead.Present -> launchPage(StatePaths().stateDir, url, read.key).toUri().toString()
            is MgmtKeyRead.Unreadable -> null.also {
                println("splice: the management key is unreadable (${read.reason}).")
                println("splice: the console can't read splice until it is.")
            }
            is MgmtKeyRead.Absent -> null.also {
                println("splice: the daemon has no management key yet; run `splice restart` and try again.")
            }
        } ?: return false
        println(if (browser.open(target)) "splice: opened the console at $url" else "splice: open the console at $url")
        return true
    }

    /** Writes the page the browser is sent to: a redirect to [url] with the key in the fragment, owner-only from the
     *  instant it exists (the law `mgmt-key` itself is written under). */
    internal fun launchPage(stateDir: Path, url: String, key: String): Path {
        val target = "$url/#k=${URLEncoder.encode(key, Charsets.UTF_8)}"
        val page = stateDir.resolve(CONSOLE_LAUNCH_PAGE)
        SecureFile.writeAtomic0600(
            page,
            "<!doctype html><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">" +
                "<meta http-equiv=\"refresh\" content=\"0;url=$target\"><title>splice</title>" +
                "<a href=\"$target\">open the splice console</a>\n",
        )
        return page
    }
}
