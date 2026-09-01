// NEW: loopback confirmation page for the OAuth callback.
// Split from OAuthLoginFlow.kt so the orchestrator is not billed for
// the HTML renderer (concentration, 2026-08-19).
package splice.app

import com.sun.net.httpserver.HttpExchange

internal class OAuthCallbackPage {

    fun respond(ex: HttpExchange, ok: Boolean, head: String, error: String?) {
        val bytes = callbackPage(ok, head, error).toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(if (ok) HTTP_OK else HTTP_BAD_REQUEST, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun callbackPage(ok: Boolean, head: String, error: String?): String {
        val safeHead = htmlEscape(head)
        val badge = if (ok) "&#10003;" else "&#10005;"
        val cls = if (ok) "ok" else "err"
        val title = if (ok) "Signed in to splice" else "Login didn’t complete"
        val sub = if (ok) {
            // Name the DESTINATION, not "your terminal": /login is usually invoked from inside a
            // Claude Code session, where there is no terminal to go back to. xAI's own CLI does
            // exactly this — "You can close this window and return to Grok Build."
            "You’re all set — close this window and return to your splice session."
        } else {
            "Something went wrong signing in. You can close this tab and try again."
        }
        val detail = if (!ok && !error.isNullOrEmpty()) "<p class=\"detail\">${htmlEscape(error)}</p>" else ""
        return callbackDocument(CallbackView(ok, cls, badge, title, sub, detail, safeHead))
    }

    /** Rendered fields for the confirmation page — a parameter object so the renderer stays 1-arg. */
    private data class CallbackView(
        val ok: Boolean,
        val cls: String,
        val badge: String,
        val title: String,
        val sub: String,
        val detail: String,
        val safeHead: String,
    )

    // A self-contained, theme-aware confirmation page (loopback-served, so all CSS/JS is inline).
    private fun callbackDocument(view: CallbackView): String = with(view) {
        """
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
}

// File-scope on purpose: const val is illegal inside a class without a companion
// (Kotlin style law — no companion objects in main sources).
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
