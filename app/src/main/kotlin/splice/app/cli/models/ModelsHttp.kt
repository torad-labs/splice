// NEW: 2026-09-22 — the one network seam `splice models` uses, and its JDK implementation.
//
// A SEPARATE SEAM FROM `splice add`'s (AddHttp) ON PURPOSE: this one carries arbitrary request
// HEADERS, because the two dialects that publish a list want different ones — an
// anthropic-passthrough endpoint expects `x-api-key` and `anthropic-version`, an openai-chat one a
// bearer. Widening AddHttp would have put that requirement on every check `splice add` runs, which
// is unrelated code.
package splice.app.cli.models

import splice.core.util.Cancellables
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val LIST_TIMEOUT_S = 10L

internal data class ModelsReply(val status: Int, val body: String)

/** One GET, or null when nothing answers. */
internal fun interface ModelsHttp {
    operator fun invoke(url: String, headers: Map<String, String>): ModelsReply?
}

internal class JdkModelsHttp(private val client: HttpClient = HttpClient.newHttpClient()) : ModelsHttp {
    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-22: 'nothing answers' is this probe's
    // normal negative (a local runtime that is not running is the common case), and the single caller
    // turns the null into the printed "nothing answers at <url>" row.
    override fun invoke(url: String, headers: Map<String, String>): ModelsReply? = Cancellables
        .runCatchingCancellable {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(LIST_TIMEOUT_S))
                .GET()
            headers.forEach { (name, value) -> builder.header(name, value) }
            val reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            ModelsReply(reply.statusCode(), reply.body())
        }
        .getOrNull()
}
