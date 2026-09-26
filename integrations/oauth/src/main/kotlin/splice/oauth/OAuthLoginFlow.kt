// NEW: the OAuth login orchestration the Node had in codex-login.mjs (never ported until now) —
// generalized to serve BOTH codex and grok (identical shape: PKCE authorize URL → loopback
// callback server → code exchange → write auth.json). Admin one-shot: its lines go out through the
// caller's TerminalOutput and a bounded runBlocking bridge lives in the CLI. The loopback bind is
// 127.0.0.1 only.
package splice.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// LoginSpec lives in LoginSpec.kt; the confirmation page lives in OAuthCallbackPage.kt
// (concentration, 2026-08-19).

private const val CALLBACK_TIMEOUT_S = 300L

/** What the pane says when a sign-in finished in a tab from an earlier attempt reaches this one. */
private const val STALE_CALLBACK_LINE =
    "splice: a sign-in from an earlier attempt arrived and was ignored; finish the sign-in in the newest tab."

/** The same, on the page that tab shows. */
private const val STALE_CALLBACK_PAGE =
    "This sign-in is from an earlier attempt and was ignored. Finish the sign-in in the newest tab."

/** `code=` in a pasted redirect URL or query fragment. */
private val CODE_PARAM = Regex("""[?&#]code=([^&\s]+)""")

/** Shortest thing accepted as a BARE code — below this it is almost certainly a stray key. */
private const val MIN_BARE_CODE = 8

// why: how often the paste reader looks for a typed line; a pasted code waits at most this long.
private const val PASTE_POLL_MS = 50L

// why: the stop waits out one poll and a line already typed, since the reader never blocks in a read.
private const val PASTE_STOP_MS = 4 * PASTE_POLL_MS

/** A running paste reader and the one way to end it (V4-251): [stop] returns once it has stopped
 *  reading, so nothing typed after the sign-in can reach it. */
private class PasteReading(private val stopSignal: CountDownLatch, private val reader: ExecutorService) {
    fun stop() {
        stopSignal.countDown()
        // Bounded, and enough: the reader checks the signal at every line boundary and every poll.
        reader.awaitTermination(PASTE_STOP_MS, TimeUnit.MILLISECONDS)
    }
}
// V4-122: ERR_BODY_CAP is LoginIo's declaration now, read from this package — one width for the
// login flow rather than one per file that renders it.

/** A class, not an `object` (LAYOUT-01): every line the flow speaks goes to [output], so each caller
 *  hands in its own — the CLI a terminal, a test a recorder — and [browser] rides with it. */
public class OAuthLoginFlow(
    private val output: TerminalOutput,
    browser: BrowserOpener = SystemBrowserOpener(output),
    /** V4-251: the console a pasted code is read from; the process's own terminal by default. */
    private val paste: PasteSource = SystemPasteSource(),
) {

    private val loginIo = LoginIo(output, browser)
    private val authClients = AuthHttpClientFactory()
    private val callbackPage = OAuthCallbackPage()

    /** Runs the browser OAuth flow to completion; returns true on success.
     *
     *  [observer], V4-132: the console's login-id/poll seam (POST/GET /api/auth/{head}/login[/{id}]),
     *  null for the CLI. Present means this call is being WATCHED off-request: [awaitCode] reports
     *  the authorize URL through it instead of opening a browser on the daemon's own desktop. */
    public suspend fun run(spec: LoginSpec, observer: LoginObserver? = null): Boolean {
        val codeRef = AtomicReference<String?>(null)
        val errRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = createServer(spec.redirectPort) ?: return false
        val pool = Executors.newSingleThreadExecutor()
        server.executor = pool
        server.createContext(spec.redirectPath) { ex ->
            handleCallback(ex, spec, codeRef, errRef, latch)
        }
        server.start()
        try {
            val code = awaitCode(spec, latch, codeRef, errRef, observer) ?: return false
            return exchangeAndPersist(spec, code)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    /** Bind the loopback callback listener; null (with a message) when the port is already taken. */
    private fun createServer(redirectPort: Int): HttpServer? = try {
        HttpServer.create(InetSocketAddress("127.0.0.1", redirectPort), 0)
    } catch (e: IOException) {
        output.line(
            "splice: can't start the login listener on 127.0.0.1:$redirectPort " +
                // SAFE-RENDER-EXEMPT[2026-08-31]: HttpServer.create bind on loopback — the IOException names a port, never file bytes
                "(is another login already running?): ${e.message}",
        )
        null
    }

    /** Open the browser, then block for the provider's callback; the authorization code or null. */
    private fun awaitCode(
        spec: LoginSpec,
        latch: CountDownLatch,
        codeRef: AtomicReference<String?>,
        errRef: AtomicReference<String?>,
        observer: LoginObserver?,
    ): String? {
        if (observer != null) {
            // Off-request: report the URL through the poll seam for the CONSOLE to open, never the
            // daemon's own (nonexistent) desktop.
            observer.announced(LoginAnnouncement(browserUrl = spec.authorizeUrl))
        } else {
            output.line("splice: opening your browser to sign in (${spec.head})…")
            if (!loginIo.openBrowser(spec.authorizeUrl)) {
                output.line("splice: open this URL to sign in:")
                output.line(spec.authorizeUrl)
            }
        }
        // LOOPBACK **OR** STDIN PASTE. A loopback callback can simply never arrive — a browser on
        // another machine, an SSH session, a container without a shared localhost, a redirect the
        // provider fires at a different port. xAI's own CLI accepts both for exactly this reason
        // ("OIDC: waiting for auth code (loopback + stdin)"), and without a second channel the only
        // outcome is a silent timeout. Racing them means whichever lands first wins; the pasted
        // value goes through the SAME exchange, so nothing about the token path changes.
        val pasting = pasteFallback(spec, latch, codeRef)
        val arrived = try {
            latch.await(CALLBACK_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            // V4-251: every way out of the wait ends the paste reader before the next prompt reads.
            pasting?.stop()
        }
        if (!arrived) {
            output.line("splice: login timed out waiting for the callback (${CALLBACK_TIMEOUT_S}s).")
            return null
        }
        errRef.get()?.let {
            output.line("splice: login failed: $it")
            return null
        }
        return codeRef.get() ?: run {
            output.line("splice: login failed: no authorization code received.")
            null
        }
    }

    /** Read a pasted `code=` value (or a whole redirect URL) from the terminal, racing the loopback.
     *
     *  V4-251: it reads ONLY while the flow waits, and never parks in a blocking read. It used to sit
     *  in readlnOrNull, which outlived the flow: once the loopback won, the next line the user typed
     *  (the answer to the next prompt) went to that parked thread and was thrown away, and the prompt
     *  needed it typed twice (take2-plans-1). Now it takes bytes only when the terminal already holds
     *  them (in canonical mode a line becomes readable whole, on Enter), and stops at the first line
     *  boundary once the wait is over. The returned [PasteReading] is that stop, which [awaitCode]
     *  calls on every way out. Null without a terminal (a detached run, the console's off-request
     *  sign-in), which prints no prompt and reads nothing. */
    private fun pasteFallback(
        spec: LoginSpec,
        latch: CountDownLatch,
        codeRef: AtomicReference<String?>,
    ): PasteReading? {
        if (!paste.interactive()) return null
        output.line("splice: if the browser cannot reach this machine, paste the redirect URL (or just the code) here:")
        val input = paste.input()
        val stop = CountDownLatch(1)
        // A named single-thread executor, the same seam [run] already uses for the loopback server's
        // handler pool, not a raw thread: a daemon, so a reader the stop could not wait out never
        // holds the JVM open, carrying the per-head name a stack dump needs.
        val reader = Executors.newSingleThreadExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-login-paste-${spec.head}"
                isDaemon = true
            }
        }
        reader.execute {
            val read = Cancellables.runCatchingCancellable { readPastes(input, latch, stop, codeRef) }
            Cancellables.discard(read, "the terminal closed or will not read; the loopback callback is still live")
        }
        reader.shutdown()
        return PasteReading(stop, reader)
    }

    /** The paste reader's loop: whole lines, only bytes already typed, and only while the flow waits. */
    private fun readPastes(
        input: InputStream,
        latch: CountDownLatch,
        stop: CountDownLatch,
        codeRef: AtomicReference<String?>,
    ) {
        val line = ByteArrayOutputStream()
        var reading = true
        while (reading) {
            val waiting = latch.count > 0L && stop.count > 0L
            reading = when {
                // A line boundary after the wait is over: everything from here on is the next prompt's.
                !waiting && line.size() == 0 -> false
                input.available() > 0 -> takeByte(input, line, latch, stop, codeRef)
                // Nothing typed yet: poll again, unless the wait is over mid-line, when what was typed
                // during the wait goes with the flow.
                else -> waiting.also { if (it) stop.await(PASTE_POLL_MS, TimeUnit.MILLISECONDS) }
            }
        }
    }

    /** One byte already typed, and at a line's end the whole line; false when reading should end. */
    private fun takeByte(
        input: InputStream,
        line: ByteArrayOutputStream,
        latch: CountDownLatch,
        stop: CountDownLatch,
        codeRef: AtomicReference<String?>,
    ): Boolean {
        val b = input.read()
        if (b < 0 || b == '\n'.code) {
            val text = line.toString(Charsets.UTF_8).also { line.reset() }
            return b >= 0 && lineTyped(text, latch, stop, codeRef)
        }
        line.write(b)
        return true
    }

    /** A whole typed line during the wait: a code ends the wait, and the reading with it, because the
     *  rest of what was typed belongs to the next prompt; anything else asks again. */
    private fun lineTyped(
        text: String,
        latch: CountDownLatch,
        stop: CountDownLatch,
        codeRef: AtomicReference<String?>,
    ): Boolean {
        if (latch.count == 0L || stop.count == 0L) return false
        val code = extractCode(text)
        if (code == null) {
            if (text.isNotBlank()) output.line("splice: that is not an authorization code; try again:")
            return true
        }
        codeRef.compareAndSet(null, code)
        latch.countDown()
        return false
    }

    /** A pasted redirect URL, a bare `code=...` fragment, or a bare code. Null when it is neither. */
    internal fun extractCode(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        CODE_PARAM.find(line)?.let { return decode(it.groupValues[1]) }
        // A bare code: no scheme, no spaces, and long enough not to be a stray keystroke.
        return line.takeIf { !it.contains("://") && !it.contains(' ') && it.length >= MIN_BARE_CODE }
    }

    private fun handleCallback(
        ex: HttpExchange,
        spec: LoginSpec,
        codeRef: AtomicReference<String?>,
        errRef: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        val params = Cancellables.runCatchingCancellable { queryParams(ex.requestURI.rawQuery.orEmpty()) }
            .onFailure {
                output.line("splice: ignoring a callback whose query does not parse: ${SafeFailureText.render(it)}")
            }
            .getOrDefault(emptyMap())
        // Only a callback carrying OUR state ends the login. A drive-by hit on the loopback port (a
        // local page, another process, a malformed-escape probe) is answered but IGNORED, so the
        // genuine provider redirect can still land — a stray request can't abort the flow.
        if (params["state"] != spec.expectedState) {
            // A callback carrying ANOTHER state is a sign-in finished in a tab from an earlier
            // attempt. It stays ignored, but it is named: silent, the pane sat until the timeout
            // while the operator believed he had signed in (rehearsal-plans-1, 2026-09-25).
            val stale = params["state"] != null
            if (stale) output.line(STALE_CALLBACK_LINE)
            Cancellables.discard(
                Cancellables.runCatchingCancellable {
                    val why = if (stale) STALE_CALLBACK_PAGE else "unexpected callback"
                    callbackPage.respond(ex, ok = false, head = spec.head, error = why)
                },
                "reply to a stray request is cosmetic; the flow keeps waiting either way",
            )
            return
        }
        try {
            val error = params["error"]
            when {
                error != null -> errRef.set(sanitize(params["error_description"] ?: error))
                params["code"].isNullOrEmpty() -> errRef.set("no authorization code in callback")
                else -> codeRef.set(params["code"])
            }
            Cancellables.discard(
                Cancellables.runCatchingCancellable {
                    callbackPage.respond(
                        ex,
                        ok = codeRef.get() != null,
                        head = spec.head,
                        error = errRef.get(),
                    )
                },
                "browser page is cosmetic; code/error refs are already recorded for the flow",
            )
        } finally {
            latch.countDown()
        }
    }

    /** Strip control/ANSI chars from provider-supplied text before it reaches the operator's terminal. */
    private fun sanitize(s: String): String = s.filter { !it.isISOControl() }.take(ERR_BODY_CAP)

    // internal (DR-73): the sentinel arm exercises this exchange boundary against a loopback
    // token endpoint without the browser/callback dance run() owns.
    internal suspend fun exchangeAndPersist(spec: LoginSpec, code: String): Boolean {
        val client = authClients.create()
        return try {
            Cancellables.runCatchingBestEffort {
                val resp: HttpResponse = client.post(spec.tokenUrl) {
                    header("Content-Type", "application/x-www-form-urlencoded")
                    header("Accept", "application/json")
                    setBody(spec.exchangeForm(code))
                }
                val bodyText = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    // Never print the provider's response body here — a provider that echoes a
                    // secret into error_description must not surface it on the operator's terminal.
                    output.line("splice: token exchange failed (HTTP ${resp.status.value})")
                    false
                } else {
                    // DR-172: a 200 alone used to mean "signed in" here. The token check and the
                    // message now live together in one place, shared with the device flow.
                    loginIo.persistIfSignedIn(spec.authPath, spec.toAuthJson(bodyText), spec.account)
                }
            }.getOrElse { e ->
                output.line("splice: token exchange error: ${SafeFailureText.render(e)}")
                false
            }
        } finally {
            client.close()
        }
    }

    private fun decode(s: String): String =
        Cancellables.runCatchingCancellable { URLDecoder.decode(s, Charsets.UTF_8) }
            .onFailure { output.line("splice: a callback value is not valid percent-encoding; using it verbatim") }
            .getOrDefault(s)

    private fun queryParams(raw: String): Map<String, String> =
        raw.split("&").filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            if (i < 0) decode(part) to "" else decode(part.substring(0, i)) to decode(part.substring(i + 1))
        }
}
