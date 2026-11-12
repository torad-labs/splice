// NEW: Oct 10, 2026 — the console's pages, served by the control plane at `/`. The pages are the drawing's own HTML,
// CSS, JS and fonts (Splice-Animator design/console), packaged under `console/` in the jar. A page carries no data:
// it reads everything from the keyed /api/* rows, so these two rows open with no key, by the dated entry in
// kt-control-route-guarded's allowlist.
//
// Oct 26, 2026: and the two rows that sign a console in with no secret in any address (ConsoleKey). `splice console`
// asks /api/console/code for a single-use code with the management key and hands the page only that code, and the page
// trades it at /api/console/key, which opens with no key because the page holds none yet, for the console key.
package splice.app.control.mount

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.http.JsonReply
import java.nio.file.Files
import java.nio.file.Path

// why: the page the bare address opens. Accounts is where a person signs in and where a limit sends them.
private const val FIRST_PAGE = "accounts.html"

internal class ConsoleMount(
    private val guard: ControlGuard,
    private val env: EnvReader = EnvReader(System::getenv),
) {

    /** A page, a stylesheet, a script, a font or a brand image (the mark, the favicon, the app icon), by a plain name:
     *  nothing else is served, and no name climbs out. */
    private val asset = Regex("""((fonts|brand)/)?[a-z0-9][a-z0-9-]*\.(html|css|js|woff2|svg|png)""")
    private val json = Json { ignoreUnknownKeys = true }

    fun register(route: Route) {
        route.get("/") { call.respondRedirect("/$FIRST_PAGE") }
        route.post("/api/console/code") {
            guard.guarded(call) { secret(call, HttpStatusCode.OK, "code", guard.console.code()) }
        }
        // Open: the page that calls it holds no key yet. It answers a code minted above, once and in time, and nothing
        // else, and what it answers is the console key, never the management key (ConsoleKey).
        route.post("/api/console/key") {
            val code = JsonScalars.str(JsonScalars.objectOrNull(json, call.receiveText()), "code")
            val key = code?.let(guard.console::trade)
            if (key == null) {
                secret(call, HttpStatusCode.Unauthorized, "error", "that sign-in code is spent, expired or unknown")
            } else {
                secret(call, HttpStatusCode.OK, "key", key)
            }
        }
        route.get("/{asset...}") {
            val name = call.parameters.getAll("asset").orEmpty().joinToString("/")
            val type = typeOf(name)
            val bytes = type?.let { read(name) }
            if (type == null || bytes == null) {
                call.respondBytes(ByteArray(0), ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@get
            }
            call.response.cacheControl(CacheControl.NoStore(null))
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(bytes, type)
        }
    }

    /** A one-field answer that no browser or proxy keeps: a code or a key is good for its one reader. */
    private suspend fun secret(call: ApplicationCall, status: HttpStatusCode, field: String, value: String) {
        call.response.cacheControl(CacheControl.NoStore(null))
        JsonReply(status, buildJsonObject { put(field, value) }.toString()).send(call)
    }

    private fun typeOf(name: String): ContentType? {
        if (!asset.matches(name)) return null
        return when (name.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html
            "css" -> ContentType.Text.CSS
            "js" -> ContentType.Text.JavaScript
            "woff2" -> ContentType("font", "woff2")
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            else -> null
        }
    }

    /** From the jar, or from SPLICE_CONSOLE_DIR when it names an absolute folder, so an edit shows on reload. */
    private fun read(name: String): ByteArray? {
        val dir = env("SPLICE_CONSOLE_DIR")?.let(Path::of)?.takeIf { it.isAbsolute }
        if (dir != null) return dir.resolve(name).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
        return ClassLoader.getSystemResourceAsStream("console/$name")?.use { it.readAllBytes() }
    }
}
