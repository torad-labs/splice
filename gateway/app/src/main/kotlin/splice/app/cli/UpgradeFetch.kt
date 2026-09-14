// NEW: v0.4.0 FEATURES.md §5 — the download seam `splice upgrade` fetches release assets through
// (https, or file:// for an acceptance fixture), and its JDK implementation. Split from
// UpgradeRelease.kt (concentration, 2026-09-13).
package splice.app.cli

import splice.core.util.Cancellables
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.time.Duration
import javax.net.ssl.SSLException

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429
private const val HTTP_SERVER_ERROR = 500
private const val FETCH_TIMEOUT_S = 300L

/** GET a URL (https or file://): the bytes, null when the asset is ABSENT (404, no such file), and
 *  [UpgradeFetchFailed] for everything else — a transport failure or a refusing status is not a
 *  missing asset and is reported as what it is (review 2026-09-14). */
internal fun interface UpgradeFetch {
    operator fun invoke(url: String): ByteArray?
}

/** [why] is authored here (a status class, an exception class), never response bytes. */
internal class UpgradeFetchFailed(val why: String) : IOException(why)

internal class JdkUpgradeFetch(
    // NORMAL, not ALWAYS: a release redirect that stepped down from HTTPS to HTTP would carry the
    // jar AND the sums it is checked against over the same downgraded hop (review 2026-09-14).
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) : UpgradeFetch {
    override fun invoke(url: String): ByteArray? = Cancellables.runCatchingCancellable {
        if (url.startsWith("file:")) {
            Files.readAllBytes(Paths.get(URI(url)))
        } else {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(FETCH_TIMEOUT_S)).GET().build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (val status = reply.statusCode()) {
                HTTP_OK -> reply.body()
                HTTP_NOT_FOUND -> null
                else -> throw UpgradeFetchFailed("HTTP $status (${statusClass(status)})")
            }
        }
    }.getOrElse { e ->
        when (e) {
            is UpgradeFetchFailed -> throw e
            is NoSuchFileException -> null
            else -> throw UpgradeFetchFailed(transportClass(e))
        }
    }

    private fun statusClass(status: Int): String = when {
        status == HTTP_FORBIDDEN -> "forbidden"
        status == HTTP_TOO_MANY -> "rate limited, retry later"
        status >= HTTP_SERVER_ERROR -> "server error, retry later"
        else -> "refused"
    }

    private fun transportClass(e: Throwable): String = when (e) {
        is UnknownHostException -> "host not found (DNS)"
        is ConnectException -> "connection refused"
        is HttpTimeoutException -> "timed out after ${FETCH_TIMEOUT_S}s"
        is SSLException -> "TLS handshake failed"
        is IOException -> "connection failed"
        else -> "request failed"
    }
}
