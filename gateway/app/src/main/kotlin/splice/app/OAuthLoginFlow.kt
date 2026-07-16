// NEW: the OAuth login orchestration the Node had in codex-login.mjs (never ported until now) —
// generalized to serve BOTH codex and grok (identical shape: PKCE authorize URL → loopback
// callback server → code exchange → write auth.json). Admin one-shot; :app is wall-exempt for
// println + a bounded runBlocking bridge lives in the CLI. The loopback bind is 127.0.0.1 only.
package splice.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Everything the flow needs for one provider's login (built by LoginCommand per head). */
public data class LoginSpec(
    val head: String,
    val authorizeUrl: String, // already built with challenge + state + nonce
    val redirectPort: Int,
    val redirectPath: String, // "/auth/callback" (codex) or "/callback" (grok)
    val expectedState: String,
    val tokenUrl: String,
    val exchangeForm: String, // x-www-form-urlencoded body (closes over code slot via {CODE})
    val authPath: Path,
    /** token-endpoint response body → the auth.json content to persist. */
    val toAuthJson: (responseBody: String) -> String,
)

public object OAuthLoginFlow {

    private const val CALLBACK_TIMEOUT_S = 300L
    private const val HTTP_OK = 200
    private const val HTTP_BAD_REQUEST = 400
    private const val ERR_BODY_CAP = 300

    /** Runs the browser OAuth flow to completion; returns true on success. */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount")
    public suspend fun run(spec: LoginSpec): Boolean {
        val codeRef = AtomicReference<String?>(null)
        val errRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", spec.redirectPort), 0)
        val pool = Executors.newSingleThreadExecutor()
        server.executor = pool
        server.createContext(spec.redirectPath) { ex ->
            handleCallback(ex, spec.head, spec.expectedState, codeRef, errRef, latch)
        }
        server.start()
        try {
            println("splice: opening your browser to sign in (${spec.head})…")
            if (!openBrowser(spec.authorizeUrl)) {
                println("splice: open this URL to sign in:")
                println(spec.authorizeUrl)
            }
            if (!latch.await(CALLBACK_TIMEOUT_S, TimeUnit.SECONDS)) {
                println("splice: login timed out waiting for the callback.")
                return false
            }
            errRef.get()?.let {
                println("splice: login failed: $it")
                return false
            }
            val code = codeRef.get() ?: run {
                println("splice: login failed: no authorization code received.")
                return false
            }
            return exchangeAndPersist(spec, code)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Suppress("LongParameterList") // the callback closure threads its result refs + latch
    private fun handleCallback(
        ex: HttpExchange,
        head: String,
        expectedState: String,
        codeRef: AtomicReference<String?>,
        errRef: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        try {
            val params = queryParams(ex.requestURI.rawQuery.orEmpty())
            val error = params["error"]
            when {
                error != null -> errRef.set(params["error_description"] ?: error)
                params["state"] != expectedState -> errRef.set("OAuth state mismatch")
                params["code"].isNullOrEmpty() -> errRef.set("no authorization code in callback")
                else -> codeRef.set(params["code"])
            }
            val ok = codeRef.get() != null
            val bytes = callbackPage(ok, head, errRef.get()).toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(if (ok) HTTP_OK else HTTP_BAD_REQUEST, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } finally {
            latch.countDown()
        }
    }

    // A self-contained, theme-aware confirmation page (loopback-served, so all CSS/JS is inline).
    @Suppress("MaxLineLength", "MaximumLineLength", "LongMethod") // inline CSS is intentionally long
    private fun callbackPage(ok: Boolean, head: String, error: String?): String {
        val safeHead = htmlEscape(head)
        val badge = if (ok) "&#10003;" else "&#10005;"
        val cls = if (ok) "ok" else "err"
        val title = if (ok) "Signed in to splice" else "Login didn’t complete"
        val sub = if (ok) {
            "You’re all set — close this tab and head back to your terminal."
        } else {
            "Something went wrong signing in. You can close this tab and try again."
        }
        val detail = if (!ok && !error.isNullOrEmpty()) "<p class=\"detail\">${htmlEscape(error)}</p>" else ""
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>splice — ${if (ok) "signed in" else "sign-in failed"}</title>
            <style>
              :root { color-scheme: light dark; }
              * { box-sizing: border-box; margin: 0; }
              body { min-height: 100vh; display: grid; place-items: center; padding: 24px;
                font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
                color: #e6e7eb; background: radial-gradient(1100px 560px at 50% -12%, #15171f, #0b0c10); }
              .card { text-align: center; padding: 46px 42px; border-radius: 20px; max-width: 440px;
                background: rgba(255,255,255,.035); border: 1px solid rgba(255,255,255,.09);
                box-shadow: 0 24px 70px rgba(0,0,0,.45); animation: rise .5s cubic-bezier(.2,.7,.2,1) both; }
              @keyframes rise { from { opacity: 0; transform: translateY(10px) scale(.98); } }
              .badge { width: 70px; height: 70px; border-radius: 50%; display: grid; place-items: center;
                margin: 0 auto 24px; font-size: 34px; font-weight: 700; animation: pop .45s .12s both; }
              @keyframes pop { from { transform: scale(.4); opacity: 0; } }
              .ok .badge { background: rgba(52,211,153,.15); color: #34d399; box-shadow: 0 0 0 7px rgba(52,211,153,.06); }
              .err .badge { background: rgba(248,113,113,.15); color: #f87171; box-shadow: 0 0 0 7px rgba(248,113,113,.06); }
              h1 { font-size: 21px; font-weight: 640; letter-spacing: -.012em; }
              p { color: #9aa1ad; margin-top: 11px; }
              .detail { margin-top: 14px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                font-size: 12.5px; color: #d98a8a; word-break: break-word; }
              .head { display: inline-block; margin-top: 20px; padding: 5px 13px; border-radius: 999px;
                background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.09);
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; color: #c7cad1; }
              .brand { margin-top: 28px; font-size: 11px; letter-spacing: .22em; text-transform: uppercase; color: #565c69; }
              @media (prefers-color-scheme: light) {
                body { color: #1a1c22; background: radial-gradient(1100px 560px at 50% -12%, #fff, #eef0f3); }
                .card { background: #fff; border-color: #e7e8ec; box-shadow: 0 22px 55px rgba(20,22,30,.09); }
                p { color: #6b7280; } .detail { color: #b91c1c; }
                .head { background: #f4f5f7; border-color: #e7e8ec; color: #374151; } .brand { color: #9aa1ad; }
              }
            </style></head>
            <body><main class="card $cls">
              <div class="badge">$badge</div>
              <h1>$title</h1>
              <p>$sub</p>
              $detail
              <div class="head">head&nbsp;·&nbsp;$safeHead</div>
              <div class="brand">&#10022; splice</div>
            </main>
            <script>setTimeout(function(){try{window.close()}catch(e){}}, 2600)</script>
            </body></html>
        """.trimIndent()
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun exchangeAndPersist(spec: LoginSpec, code: String): Boolean {
        val client = HttpClient(CIO)
        try {
            val resp: HttpResponse = client.post(spec.tokenUrl) {
                header("Content-Type", "application/x-www-form-urlencoded")
                header("Accept", "application/json")
                setBody(spec.exchangeForm.replace("{CODE}", code))
            }
            val bodyText = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                println("splice: token exchange failed (HTTP ${resp.status.value}): ${bodyText.take(ERR_BODY_CAP)}")
                return false
            }
            java.nio.file.Files.createDirectories(spec.authPath.parent)
            java.nio.file.Files.writeString(spec.authPath, spec.toAuthJson(bodyText))
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    spec.authPath,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
            println("splice: signed in — credentials written to ${spec.authPath}")
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            println("splice: token exchange error: ${e.message}")
            return false
        } finally {
            client.close()
        }
    }

    private fun queryParams(raw: String): Map<String, String> =
        raw.split("&").filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            if (i < 0) {
                part to ""
            } else {
                val key = java.net.URLDecoder.decode(part.substring(0, i), Charsets.UTF_8)
                key to java.net.URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8)
            }
        }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "SwallowedException")
    private fun openBrowser(url: String): Boolean = try {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
            else -> return false
        }
        ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }
}
