// NEW: v0.4.0 FEATURES.md §10 — the JDK implementation of the local-runtime probe's network seam.
// Moved out of :dialects-openai-chat (V4-103): the seam (LocalHttp/LocalHttpReply) lives in
// :upstream, and the JDK client — the thing that actually dials — lives here in :app.
package splice.app.provider.local

import splice.app.server.HealthHttp
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.io.IOException
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

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
    /** How long one request may take to answer; null bounds it by its URL, as every probe always was. */
    private val timeout: Duration? = null,
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

    /** The external state probe needs a bounded answer, without waiting for a non-2xx body at all. */
    fun getBounded(url: String, maxBytes: Int): LocalHttpReply {
        val reply = client.send(request("GET", url, null), HttpResponse.BodyHandlers.ofInputStream())
        return reply.body().use { stream ->
            val status = reply.statusCode()
            val body = if (status in HTTP_OK until HTTP_MULT_CHOICE) {
                val bytes = stream.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) throw LocalHttpBodyTooLarge()
                bytes.decodeToString(throwOnInvalidSequence = true)
            } else {
                ""
            }
            LocalHttpReply(status, body)
        }
    }

    private fun send(method: String, url: String, body: String?): LocalHttpReply {
        val reply = client.send(request(method, url, body), HttpResponse.BodyHandlers.ofString())
        return LocalHttpReply(reply.statusCode(), reply.body())
    }

    private fun request(method: String, url: String, body: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(URI(url))
            // Only the live turn may take minutes; a probe POST (Ollama /api/show) is bounded
            // like a GET, so a wedged runtime cannot hold head assembly for 120 s per model.
            // A caller that names its own deadline (a completion readiness probe) gets that one.
            .timeout(timeout ?: Duration.ofSeconds(if (url.endsWith(LIVE_PATH)) LIVE_TIMEOUT_S else PROBE_TIMEOUT_S))
            .header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
    }
}

internal class LocalHttpBodyTooLarge : IOException("HTTP body is too large")

/** Legacy health checks share a default-trust client. Completion checks share a separate client per pool with its
 *  provider's trust and request timeout. A client and its selector thread are created on first use. */
internal class JdkHealthHttp(
    trust: X509TrustManager? = null,
    private val timeout: Duration? = null,
) : HealthHttp {
    private val client by lazy {
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
        if (trust != null) builder.sslContext(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) })
        builder.build()
    }

    override fun with(headers: Map<String, String>): LocalHttp = JdkLocalHttp(headers, client, timeout)
}
