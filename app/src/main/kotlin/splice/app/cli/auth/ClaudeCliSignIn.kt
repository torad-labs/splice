// NEW: signin-e2e (signin-claude-cli) — `splice login <client-head>` runs the same Claude Code sign-in
// the console's Sign in runs: when the daemon is answering, it starts POST /api/auth/{head}/login,
// prints and opens the sign-in page Claude Code names, reads a pasted code from stdin when needed
// and submits it to /code; otherwise it runs NativeClaudeAuth directly in-process.
package splice.app.cli.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.auth.claude.NativeAuthLink
import splice.app.auth.claude.NativeClaudeAuth
import splice.app.auth.claude.NativeClaudeAuthRun
import splice.app.auth.claude.NativeSignIn
import splice.app.cli.AdminSupport
import splice.client.wrap.WrappedHead
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.oauth.BrowserOpener
import splice.oauth.SystemBrowserOpener
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

// why: polls the daemon's sign-in status four times a second so the browser URL and completion land promptly.
private const val SIGNIN_POLL_INTERVAL_MS = 250L

// why: caps how long `splice login <claude-head>` waits for a sign-in before giving up cleanly.
private const val MAX_SIGNIN_MS = 10L * 60L * 1000L

internal class ClaudeCliSignIn(
    private val output: TerminalOutput,
    private val browser: BrowserOpener = SystemBrowserOpener(output),
    private val stdin: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val ticker: Ticker = ProcessTicker(),
) {
    private val client = ClaudeCliSignInHttp()

    internal suspend fun login(headKey: String, topology: Topology): Boolean {
        val port = AdminSupport.controlPort(topology)
        val key = client.mgmtKey()
        if (key != null) {
            val started = client.postJson("http://127.0.0.1:$port/api/auth/$headKey/login", key, "{}")
            if (started != null) return followDaemon(port, key, headKey, started)
        }
        return runLocally()
    }

    private suspend fun followDaemon(port: Int, key: String, headKey: String, initial: JsonObject): Boolean {
        val id = JsonScalars.str(initial, "id") ?: return reportFailure(initial)
        val tracker = PollTracker()
        var status: JsonObject? = initial
        val deadline = System.currentTimeMillis() + MAX_SIGNIN_MS
        while (status != null && System.currentTimeMillis() < deadline) {
            val terminal = checkTerminal(status)
            if (terminal != null) return terminal
            tracker.advance(status, port, key, headKey, id)
            ticker.awaitTick(SIGNIN_POLL_INTERVAL_MS)
            status = client.getJson("http://127.0.0.1:$port/api/auth/$headKey/login/$id", key)
        }
        output.line("splice: sign-in timed out")
        return false
    }

    private fun checkTerminal(status: JsonObject): Boolean? {
        val state = JsonScalars.str(status, "state").orEmpty()
        return when (state) {
            "signed_in", "live_after_restart" -> {
                val label = JsonScalars.str(status, "label")
                output.line(if (label != null) "splice: signed in as $label" else "splice: signed in")
                true
            }
            "failed" -> reportFailure(status)
            else -> null
        }
    }

    private inner class PollTracker {
        private var announcedUrl: String? = null
        private var codeSent = false

        fun advance(status: JsonObject, port: Int, key: String, headKey: String, id: String) {
            val url = JsonScalars.str(status, "browser_url") ?: JsonScalars.str(status, "verification_uri")
            if (url != null && url != announcedUrl) {
                announcedUrl = url
                announceLink(url)
            }
            val needsPaste = (status["paste_code"] as? JsonPrimitive)?.booleanOrNull == true
            val canPaste = needsPaste && !codeSent
            if (canPaste && announcedUrl != null) {
                codeSent = true
                submitPastedCode(port, key, headKey, id)
            }
        }
    }

    private fun submitPastedCode(port: Int, key: String, headKey: String, id: String) {
        output.line("Paste the code from the sign-in page:")
        val line = Cancellables.runCatchingBestEffort { stdin.readLine() }.getOrNull()?.trim().orEmpty()
        if (line.isEmpty()) return
        val body = buildJsonObject { put("code", line) }.toString()
        client.postJson("http://127.0.0.1:$port/api/auth/$headKey/login/$id/code", key, body)
    }

    private suspend fun runLocally(): Boolean {
        val home = UserHome.dir()
        val auth = NativeClaudeAuth(WrappedHead(home), mapOf("HOME" to home.toString()), ProcessDispatchers().io())
        val configDir = Files.createTempDirectory("splice-cli-claude-")
        return try {
            when (val started = auth.begin(configDir)) {
                is NativeSignIn.Refused -> {
                    output.line("splice: ${started.reason}")
                    false
                }
                is NativeSignIn.Running -> driveLocal(started.run)
            }
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private suspend fun driveLocal(run: NativeClaudeAuthRun): Boolean = run.use { child ->
        val prompted = AtomicBoolean(false)
        child.await { link: NativeAuthLink ->
            announceLink(link.url)
            if (link.pasteBack && prompted.compareAndSet(false, true)) {
                output.line("Paste the code from the sign-in page:")
                val line = Cancellables.runCatchingBestEffort { stdin.readLine() }.getOrNull()?.trim().orEmpty()
                if (line.isNotEmpty()) child.submit(line)
            }
        }
    }

    private fun announceLink(url: String) {
        output.line("Open this page to sign in:")
        output.line("  $url")
        browser.open(url)
    }

    private fun reportFailure(status: JsonObject): Boolean {
        val reason = JsonScalars.str(status, "failure_reason") ?: JsonScalars.str(status, "error")
        output.line(if (reason != null) "splice: $reason" else "splice: Claude sign-in did not complete")
        return false
    }
}
