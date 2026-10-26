// NEW: Oct 10, 2026 — `splice console`: starts the daemon if it isn't running and opens the console signed in, the
// way the removed `splice dashboard` did (2026-09-24). The browser is sent to a 0600 page in the state folder that
// redirects to the console, and only the page's path is on the command line: a secret on a command line is readable
// by every local process (Jupyter's use_redirect_file, for the same reason).
//
// Oct 26, 2026: no address carries a key. Until this day the redirect carried the management key in its fragment, and
// on Sep 29 a browser tool printed such an address into a conversation that went to a model provider. The command now
// asks the daemon for a single-use code with the management key, and the page carries only that code, which the
// console trades once for its own key and clears from the address (ConsoleKey, console/api.js). A code that is spent or
// has expired opens nothing, so an address a history, a log or a tool keeps opens nothing either. A daemon older than
// this splice hands out no code, and then no page is written: the key is never put in one instead. `--no-browser`
// writes the page and prints its file address for a session's browser tool, so no desktop browser spends the code.
package splice.app.cli.status

import kotlinx.serialization.json.Json
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.core.config.CONSOLE_LAUNCH_PAGE
import splice.core.config.DASHBOARD_PAGE
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.oauth.BrowserOpener
import splice.oauth.SystemBrowserOpener
import java.nio.file.Files
import java.nio.file.Path

/** `splice console --no-browser`: writes the page and prints its address instead of opening the desktop's browser. */
internal const val CONSOLE_NO_BROWSER = "--no-browser"

// A daemon busy with turns still hands out a code well inside this.
private const val MINT_MS = 10_000

internal class ConsoleCommand(
    private val browser: BrowserOpener = SystemBrowserOpener(TerminalOutput { println(it) }),
    private val out: TerminalOutput = TerminalOutput { println(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What a code may be made of before it goes into the page: URL-safe characters only, so no answer, whoever sent
     *  it, can write markup into a page the browser opens. */
    private val codeShape = Regex("[A-Za-z0-9_-]+")

    internal fun open(openBrowser: Boolean = true): Boolean {
        val port = AdminSupport.controlPort()
        if (!LifecycleWiring.ensureDaemon(port)) {
            out.line("splice: the daemon isn't running and couldn't be started.")
            return false
        }
        return openOn(port, AdminSupport.readMgmtKey(), StatePaths().stateDir, openBrowser)
    }

    internal fun openOn(port: Int, key: MgmtKeyRead, stateDir: Path, openBrowser: Boolean): Boolean {
        // The pages earlier splices wrote carry the management key itself: none outlives a run, whatever it comes to.
        listOf(CONSOLE_LAUNCH_PAGE, DASHBOARD_PAGE).forEach { Files.deleteIfExists(stateDir.resolve(it)) }
        val url = "http://127.0.0.1:$port"
        val code = codeFor(url, key) ?: return false
        val page = launchPage(stateDir, url, code)
        val where = "this page in a browser within two minutes to sign in. It signs in one browser, once: " +
            page.toUri()
        when {
            openBrowser && browser.openPage(page) -> out.line("splice: opened the console at $url")
            openBrowser -> out.line("splice: no browser opened. Open $where")
            else -> out.line("splice: open $where")
        }
        return true
    }

    /** A single-use code for the management key on disk, or null, with the reason said, when there is no key to ask
     *  with. */
    private fun codeFor(url: String, key: MgmtKeyRead): String? = when (key) {
        is MgmtKeyRead.Present -> code(url, key.key)
        is MgmtKeyRead.Unreadable -> null.also {
            out.line("splice: the management key is unreadable (${key.reason}).")
            out.line("splice: the console can't read splice until it is.")
        }
        is MgmtKeyRead.Absent -> null.also {
            out.line("splice: the daemon has no management key yet; run `splice restart` and try again.")
        }
    }

    /** A single-use code the daemon minted for the management [key], or null, with the reason said, when it hands out
     *  none. The key goes only into the request's Authorization header. */
    private fun code(url: String, key: String): String? {
        val reply = when (val reading = ControlPlaneClient.post("$url/api/console/code", key, "{}", MINT_MS)) {
            is PostReading.Answered -> reading.reply
            PostReading.NotConnected, PostReading.CutAfterSending -> null
        }
        val status = reply?.status
        val minted = reply?.takeIf { status in ControlPlaneClient.OK_RANGE }
            ?.let { JsonScalars.str(JsonScalars.objectOrNull(json, it.body), "code") }
        if (minted != null && codeShape.matches(minted)) return minted
        out.line(refusal(status))
        return null
    }

    /** What to tell the user when the daemon's answer, of [status] or none at all, carried no code. */
    private fun refusal(status: Int?): String = when (status) {
        null -> "splice: the daemon didn't answer, so the console can't sign in. Run `splice console` again."
        HttpStatus.NOT_FOUND ->
            "splice: the daemon that is running is older than this splice and can't sign the console in. " +
                "Run `splice restart`, then `splice console` again."
        HttpStatus.UNAUTHORIZED ->
            "splice: the daemon refused the management key on disk, as it does when the key changed after it " +
                "started. Run `splice restart`, then `splice console` again."
        else -> "splice: the daemon handed out no sign-in code (HTTP $status), so the console can't sign in."
    }

    /** Writes the page the browser is sent to: a redirect to [url] with the single-use [code] in the fragment, which
     *  never reaches a server or a log, owner-only from the instant it exists (the law `mgmt-key` is written under). */
    private fun launchPage(stateDir: Path, url: String, code: String): Path {
        val target = "$url/#c=$code"
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
