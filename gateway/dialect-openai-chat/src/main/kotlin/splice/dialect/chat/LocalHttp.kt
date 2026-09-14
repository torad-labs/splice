// NEW: v0.4.0 FEATURES.md §10 — the runtimes splice can name, and the one network seam the local
// runtime probe speaks through (with its JDK implementation, which carries the provider's headers).
// Split from LocalRuntimeProbe.kt (concentration, 2026-09-13).
package splice.dialect.chat

import splice.core.util.Cancellables
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val PROBE_TIMEOUT_S = 5L
private const val LIVE_TIMEOUT_S = 120L

public enum class LocalRuntimeKind(public val label: String) {
    OLLAMA("Ollama"),
    LM_STUDIO("LM Studio"),
    VLLM("vLLM"),
    OPENAI_COMPATIBLE("OpenAI-compatible"),
}

public data class LocalHttpReply(val status: Int, val body: String)

/** The one seam to the network: GET or POST a URL, or null when the runtime is unreachable. */
public fun interface LocalHttp {
    public operator fun invoke(method: String, url: String, body: String?): LocalHttpReply?
}

/** [headers] ride on every probe request: the provider's static headers and its bearer, so a runtime
 *  that guards /v1/models (vLLM --api-key) answers the probe the way it answers a turn. */
public class JdkLocalHttp(
    private val headers: Map<String, String> = emptyMap(),
    private val client: HttpClient = HttpClient.newHttpClient(),
) : LocalHttp {
    override fun invoke(method: String, url: String, body: String?): LocalHttpReply? = Cancellables
        .runCatchingCancellable {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(if (body == null) PROBE_TIMEOUT_S else LIVE_TIMEOUT_S))
                .header("Content-Type", "application/json")
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
                )
                .build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
            LocalHttpReply(reply.statusCode(), reply.body())
        }
        .getOrNull()
}
