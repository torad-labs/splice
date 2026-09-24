// NEW: 2026-09-24 — the console page as the control plane serves it: the built HTML with the
// management key printed into its head, so a console loaded from this daemon never asks for the key.
// The operator, after one gate too many: "we shouldn't need a management key". The key still guards
// every /api route; what changed is who hands it to the console.
//
// Only the operator's own loopback browser can read this page: ControlGuard.refuseForeignHosts
// answers a non-loopback Host (a DNS-rebinding page) with 403 before any route runs, and the control
// plane sends no CORS header, so a cross-origin page gets an opaque response it cannot read.
// frame-ancestors 'none' keeps the now keyless console out of another site's frame (a framed console
// could be clicked through), and no-store keeps a page carrying the key out of the disk cache.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import splice.app.control.DashboardPage
import splice.core.config.MgmtKey

/** The name of the meta element the console reads its key from (console/src/shared/api). */
internal const val CONSOLE_KEY_META = "splice-mgmt-key"

// why: the opening tag of the page's head, attributes allowed, in any case the build emits.
private val HEAD_OPEN = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

internal class ServedConsole(private val page: DashboardPage, private val mgmtKey: MgmtKey) {

    suspend fun respond(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
        call.respondText(withKey(page(), mgmtKey.get()), ContentType.Text.Html)
    }

    /** [html] with the key as the first element of its head. A page with no head (the placeholder
     *  served when no build is present) is returned as it is, and the console falls back to its
     *  key gate. */
    internal fun withKey(html: String, key: String): String {
        val head = HEAD_OPEN.find(html) ?: return html
        val at = head.range.last + 1
        return html.substring(0, at) + "<meta name=\"$CONSOLE_KEY_META\" content=\"${attribute(key)}\">" +
            html.substring(at)
    }

    // The key file is minted as hex, but it is a file the operator can edit: whatever it holds is
    // printed as an attribute value and never as markup.
    private fun attribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
