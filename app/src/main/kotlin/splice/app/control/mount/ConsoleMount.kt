// NEW: Oct 10, 2026 — the console's pages, served by the control plane at `/`. The pages are the drawing's own HTML,
// CSS, JS and fonts (Splice-Animator design/console), packaged under `console/` in the jar. A page carries no data:
// it reads everything from the keyed /api/* rows with the management key `splice console` hands it, so these two
// rows open with no key, by the dated entry in kt-control-route-guarded's allowlist.
package splice.app.control.mount

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

// why: the page the bare address opens. Accounts is where a person signs in and where a limit sends them.
private const val FIRST_PAGE = "accounts.html"

internal class ConsoleMount(private val env: EnvReader = EnvReader(System::getenv)) {

    /** A page, a stylesheet, a script or a font, by a plain name: nothing else is served, and no name climbs out. */
    private val asset = Regex("""(fonts/)?[a-z0-9][a-z0-9-]*\.(html|css|js|woff2)""")

    fun register(route: Route) {
        route.get("/") { call.respondRedirect("/$FIRST_PAGE") }
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

    private fun typeOf(name: String): ContentType? {
        if (!asset.matches(name)) return null
        return when (name.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html
            "css" -> ContentType.Text.CSS
            "js" -> ContentType.Text.JavaScript
            "woff2" -> ContentType("font", "woff2")
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
