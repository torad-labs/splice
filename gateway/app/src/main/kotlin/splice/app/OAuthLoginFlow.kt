// NEW: the OAuth login orchestration the Node had in codex-login.mjs (never ported until now) —
// generalized to serve BOTH codex and grok (identical shape: PKCE authorize URL → loopback
// callback server → code exchange → write auth.json). Admin one-shot; :app is wall-exempt for
// println + a bounded runBlocking bridge lives in the CLI. The loopback bind is 127.0.0.1 only.
package splice.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// LoginSpec lives in LoginSpec.kt; the confirmation page lives in OAuthCallbackPage.kt
// (concentration, 2026-08-19).

public object OAuthLoginFlow {

    private val loginIo = LoginIo()
    private val authClients = AuthHttpClientFactory()
    private val callbackPage = OAuthCallbackPage()

    private const val CALLBACK_TIMEOUT_S = 300L

    /** `code=` in a pasted redirect URL or query fragment. */
    private val CODE_PARAM = Regex("""[?&#]code=([^&\s]+)""")

    /** Shortest thing accepted as a BARE code — below this it is almost certainly a stray key. */
    private const val MIN_BARE_CODE = 8
    private const val ERR_BODY_CAP = 300

    /** Runs the browser OAuth flow to completion; returns true on success. */
    public suspend fun run(spec: LoginSpec): Boolean {
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
            val code = awaitCode(spec, latch, codeRef, errRef) ?: return false
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
        println(
            "splice: can't start the login listener on 127.0.0.1:$redirectPort " +
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
    ): String? {
        println("splice: opening your browser to sign in (${spec.head})…")
        if (!loginIo.openBrowser(spec.authorizeUrl)) {
            println("splice: open this URL to sign in:")
            println(spec.authorizeUrl)
        }
        // LOOPBACK **OR** STDIN PASTE. A loopback callback can simply never arrive — a browser on
        // another machine, an SSH session, a container without a shared localhost, a redirect the
        // provider fires at a different port. xAI's own CLI accepts both for exactly this reason
        // ("OIDC: waiting for auth code (loopback + stdin)"), and without a second channel the only
        // outcome is a silent timeout. Racing them means whichever lands first wins; the pasted
        // value goes through the SAME exchange, so nothing about the token path changes.
        pasteFallback(spec, latch, codeRef)
        if (!latch.await(CALLBACK_TIMEOUT_S, TimeUnit.SECONDS)) {
            println("splice: login timed out waiting for the callback (${CALLBACK_TIMEOUT_S}s).")
            return null
        }
        errRef.get()?.let {
            println("splice: login failed: $it")
            return null
        }
        return codeRef.get() ?: run {
            println("splice: login failed: no authorization code received.")
            null
        }
    }

    /** Read a pasted `code=` value (or a whole redirect URL) from stdin, racing the loopback.
     *
     *  Daemon thread on purpose: when the loopback wins, this reader is still parked on a blocking
     *  read that nothing will ever satisfy, and a non-daemon thread would keep the JVM alive after
     *  a successful login. Silently no-ops without a console, which is also the detached case. */
    private fun pasteFallback(spec: LoginSpec, latch: CountDownLatch, codeRef: AtomicReference<String?>) {
        if (System.console() == null) return
        println("splice: if the browser cannot reach this machine, paste the redirect URL (or just the code) here:")
        // A named single-thread executor, the same seam [run] already uses for the loopback server's
        // handler pool — not a raw thread. The reader thread keeps both properties the old one had:
        // it is a daemon (see above) and it carries the per-head name a stack dump needs. shutdown()
        // retires the executor once this one task finishes; it does NOT interrupt the parked read,
        // so the "loopback wins while stdin is still blocked" case behaves exactly as before.
        val reader = Executors.newSingleThreadExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-login-paste-${spec.head}"
                isDaemon = true
            }
        }
        reader.execute {
            val pasted = Cancellables.runCatchingCancellable {
                generateSequence(::readlnOrNull).forEach { line ->
                    if (latch.count == 0L) return@execute // the loopback already won
                    extractCode(line)?.let { code ->
                        codeRef.compareAndSet(null, code)
                        latch.countDown()
                        return@execute
                    }
                    if (line.isNotBlank()) println("splice: that is not an authorization code — try again:")
                }
            }
            Cancellables.discard(pasted, "stdin closed or unreadable; the loopback callback is still live")
        }
        reader.shutdown()
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
        val params = runCatching { queryParams(ex.requestURI.rawQuery.orEmpty()) }.getOrDefault(emptyMap())
        // Only a callback carrying OUR state ends the login. A drive-by hit on the loopback port (a
        // local page, another process, a malformed-escape probe) is answered but IGNORED, so the
        // genuine provider redirect can still land — a stray request can't abort the flow.
        if (params["state"] != spec.expectedState) {
            Cancellables.discard(
                runCatching { callbackPage.respond(ex, ok = false, head = spec.head, error = "unexpected callback") },
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
                runCatching {
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
            Cancellables.runCatchingCancellable {
                val resp: HttpResponse = client.post(spec.tokenUrl) {
                    header("Content-Type", "application/x-www-form-urlencoded")
                    header("Accept", "application/json")
                    setBody(spec.exchangeForm(code))
                }
                val bodyText = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    // Never print the provider's response body here — a provider that echoes a
                    // secret into error_description must not surface it on the operator's terminal.
                    println("splice: token exchange failed (HTTP ${resp.status.value})")
                    false
                } else {
                    loginIo.writeCredentialFile(spec.authPath, spec.toAuthJson(bodyText))
                    println("splice: signed in — credentials written to ${spec.authPath}")
                    true
                }
            }.getOrElse { e ->
                println("splice: token exchange error: ${SafeFailureText.render(e)}")
                false
            }
        } finally {
            client.close()
        }
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    private fun queryParams(raw: String): Map<String, String> =
        raw.split("&").filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            if (i < 0) decode(part) to "" else decode(part.substring(0, i)) to decode(part.substring(i + 1))
        }
}
