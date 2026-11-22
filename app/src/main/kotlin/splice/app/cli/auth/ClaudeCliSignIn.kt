// NEW: signin-e2e (signin-claude-cli) — `splice login <client-head>` runs the same Claude Code sign-in
// the console's Sign in runs: when the daemon is answering, it starts POST /api/auth/{head}/login,
// prints and opens the sign-in page Claude Code names, reads a pasted code from stdin when needed
// and submits it to /code; otherwise it runs NativeClaudeAuth directly in-process.
package splice.app.cli.auth

import kotlinx.serialization.json.Json
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
import splice.client.wrap.WrapState
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.oauth.BrowserOpener
import splice.oauth.SystemBrowserOpener
import splice.topology.TopologyStatePaths
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

// why: bounds each control-plane poll during a CLI Claude sign-in so a stuck daemon cannot hang forever.
private const val HTTP_TIMEOUT_S = 15L

// why: polls the daemon's sign-in status four times a second so the browser URL and completion land promptly.
private const val POLL_INTERVAL_MS = 250L

// why: caps how long `splice login <claude-head>` waits for a sign-in before giving up cleanly.
private const val MAX_SIGNIN_MS = 10L * 60L * 1000L

// why: HTTP status codes 200..499 carry a structured JSON body from the control plane; 5xx/0 are transport failures.
private const val MIN_HTTP_STATUS = 200
private const val MAX_HTTP_STATUS = 499

internal class ClaudeCliSignIn(
    private val output: TerminalOutput,
    private val browser: BrowserOpener = SystemBrowserOpener(output),
    private val stdin: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val ticker: Ticker = ProcessTicker(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
        .build()

    internal suspend fun login(headKey: String, topology: Topology): Boolean {
        val port = AdminSupport.controlPort(topology)
        val key = mgmtKey()
        if (key != null) {
            val started = startOnDaemon(port, key, headKey)
            if (started != null) return followDaemon(port, key, headKey, started)
        }
        return runLocally()
    }

    private fun mgmtKey(): String? {
        val stateDir = TopologyStatePaths.stateDir(System::getenv)
        return when (val read = MgmtKeyFile.read(stateDir)) {
            is MgmtKeyRead.Present -> read.key
            else -> null
        }
    }

    private fun startOnDaemon(port: Int, key: String, headKey: String): JsonObject? =
        postJson("http://127.0.0.1:$port/api/auth/$headKey/login", key, "{}")

    private suspend fun followDaemon(port: Int, key: String, headKey: String, initial: JsonObject): Boolean {
        val id = JsonScalars.str(initial, "id") ?: return reportFailure(initial)
        val tracker = PollTracker()
        var status: JsonObject? = initial
        val deadline = System.currentTimeMillis() + MAX_SIGNIN_MS
        while (status != null && System.currentTimeMillis() < deadline) {
            val terminal = checkTerminal(status)
            if (terminal != null) return terminal
            tracker.advance(status, port, key, headKey, id)
            ticker.awaitTick(POLL_INTERVAL_MS)
            status = getJson("http://127.0.0.1:$port/api/auth/$headKey/login/$id", key)
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
        postJson("http://127.0.0.1:$port/api/auth/$headKey/login/$id/code", key, body)
    }

    private suspend fun runLocally(): Boolean {
        val envMap = mapOf("HOME" to UserHome.home().toString())
        val auth = NativeClaudeAuth(WrapState(), envMap, ProcessDispatchers().io())
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

    private fun getJson(url: String, key: String): JsonObject? = sendJson(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
            .header("Authorization", "Bearer $key")
            .GET()
            .build(),
    )

    private fun postJson(url: String, key: String, body: String): JsonObject? = sendJson(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
    )

    private fun sendJson(req: HttpRequest): JsonObject? = Cancellables.runCatchingBestEffort {
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in MIN_HTTP_STATUS..MAX_HTTP_STATUS) return@runCatchingBestEffort null
        json.parseToJsonElement(res.body()) as? JsonObject
    }.onFailure {
        SafeFailureText.render(it)
    }.getOrNull()
}
