// NEW: v0.4.0 FEATURES.md §5 — the download seam `splice upgrade` fetches release assets through
// (https, or file:// for an acceptance fixture), and its JDK implementation. Split from
// UpgradeRelease.kt (concentration, 2026-09-13).
package splice.app.cli

import splice.core.util.Cancellables
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

private const val HTTP_OK = 200
private const val FETCH_TIMEOUT_S = 300L

/** GET a URL (https or file://), or null when nothing answers. */
internal fun interface UpgradeFetch {
    operator fun invoke(url: String): ByteArray?
}

internal class JdkUpgradeFetch(
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
) : UpgradeFetch {
    override fun invoke(url: String): ByteArray? = Cancellables.runCatchingCancellable {
        if (url.startsWith("file:")) {
            Files.readAllBytes(Paths.get(URI(url)))
        } else {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(FETCH_TIMEOUT_S)).GET().build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            reply.body().takeIf { reply.statusCode() == HTTP_OK }
        }
    }.getOrNull()
}
