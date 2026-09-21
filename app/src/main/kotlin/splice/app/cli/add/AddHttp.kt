// NEW: v0.4.0 FEATURES.md §1 — the one network seam `splice add` uses for its checks, and its JDK
// implementation. Split from AddChecks.kt (concentration, 2026-09-13).
package splice.app.cli.add

import splice.core.util.Cancellables
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val PROBE_TIMEOUT_S = 10L

internal data class AddHttpReply(val status: Int, val body: String)

/** The one seam to the network: a request, or null when nothing answers. */
internal fun interface AddHttp {
    operator fun invoke(method: String, url: String, bearer: String?, body: String?): AddHttpReply?
}

internal class JdkAddHttp(private val client: HttpClient = HttpClient.newHttpClient()) : AddHttp {
    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): the one network seam of `splice add`: 'nothing answers' is the probe's normal negative and each caller turns the null into its own printed check row.
    override fun invoke(method: String, url: String, bearer: String?, body: String?): AddHttpReply? = Cancellables
        .runCatchingCancellable {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_S))
                .header("Content-Type", "application/json")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            val reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            AddHttpReply(reply.statusCode(), reply.body())
        }
        .getOrNull()
}
