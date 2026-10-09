// NEW: v0.4.0 FEATURES.md §10 — the JDK implementation of the local-runtime probe's network seam.
// Moved out of :dialects-openai-chat (V4-103): the seam (LocalHttp/LocalHttpReply) lives in
// :upstream, and the JDK client — the thing that actually dials — lives here in :app.
package splice.app.provider

import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val CONNECT_TIMEOUT_S = 2L
private const val PROBE_TIMEOUT_S = 5L
private const val LIVE_TIMEOUT_S = 120L

/** The one request that is a real turn (LocalRuntimeProbe.live); every other POST is a probe. */
private const val LIVE_PATH = "/chat/completions"

/** [headers] ride on every probe request: the provider's static headers and its bearer, so a runtime
 *  that guards /v1/models (vLLM --api-key) answers the probe the way it answers a turn. */
internal class JdkLocalHttp(
    private val headers: Map<String, String> = emptyMap(),
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build(),
) : LocalHttp {
    // A failed probe request IS the runtime being unreachable, which LocalHttp's null return spells: a transport
    // failure, a URL no request can be built for, or an interrupt. Anything else propagates.
    override fun invoke(method: String, url: String, body: String?): LocalHttpReply? = try {
        send(method, url, body)
    } catch (_: IOException) {
        null
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun send(method: String, url: String, body: String?): LocalHttpReply {
        val builder = HttpRequest.newBuilder(URI(url))
            // Only the live turn may take minutes; a probe POST (Ollama /api/show) is bounded
            // like a GET, so a wedged runtime cannot hold head assembly for 120 s per model.
            .timeout(Duration.ofSeconds(if (url.endsWith(LIVE_PATH)) LIVE_TIMEOUT_S else PROBE_TIMEOUT_S))
            .header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
        return LocalHttpReply(reply.statusCode(), reply.body())
    }
}
