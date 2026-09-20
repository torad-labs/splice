// NEW: V4-133, FEATURES.md §5/§6 — the :app-side implementation of
// splice.control.api.PlaygroundProbe: POST /api/playground's ONE independent upstream call.
//
// NAMED UpstreamPlaygroundProbe, NOT PlaygroundProbe: the fun interface it implements already owns
// that name in splice.control.api, and a same-named concrete class one package over is exactly the
// self-referential-import confusion a route implemented beside its own port would invite.
//
// ONE SHOT, NO PIPELINE — see PlaygroundRoute.kt's header for the full reason (never recorded means
// the whole turn pipeline, not only the console). This bypasses :gateway's TurnDriver and every
// dialect module and builds the smallest legal request per dialect directly: no tools, no system
// prompt, no streaming, no retry loop, no perf/trace/economics write. The credential and topology
// are read exactly as the daemon's own turn path resolves them (ManagedHead.auth, a fresh parse of
// the booted config file) so a playground failure means what it says about that head's real config,
// never an artifact of a second, drifted resolution path.
//
// A FRESH TOPOLOGY READ, NOT THE BOOTED OBJECT: playground runs are rare and interactive, so a
// per-call re-parse is simpler than threading a cached Topology through ControlPlane for one route
// — and it reads whatever an operator last saved, matching /api/topology's own read-fresh contract.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead
import splice.control.api.PlaygroundFailure
import splice.control.api.PlaygroundOutcome
import splice.control.api.PlaygroundProbe
import splice.control.api.PlaygroundResult
import splice.core.auth.Credentials
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.spi.HeaderRedaction
import java.nio.file.Files
import java.nio.file.Path

// why: matches UpstreamTransport's own connect budget — a playground probe is not a special client.
private const val CONNECT_TIMEOUT_MS = 10_000L

// why: one interactive turn, not a streamed conversation, so a full minute is generous rather than
// tight — long enough for a slow vendor to answer without holding the route open indefinitely.
private const val REQUEST_TIMEOUT_MS = 60_000L

// why: a playground reply is a debugging echo of ONE turn, not a served conversation — capped well
// under a real turn's budget so one slow or huge vendor response cannot hold the route open forever.
private const val MAX_RESPONSE_CHARS = 200_000

// why: a minimal probe still needs an answer longer than a one-line ack to be useful to read.
private const val PLAYGROUND_MAX_TOKENS = 1024

internal class UpstreamPlaygroundProbe(
    private val configPath: Path?,
    private val client: HttpClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    },
) : PlaygroundProbe {
    private val json = Json { ignoreUnknownKeys = true }

    // Each step below is its own function (ReturnCount: max 3 per function) rather than one long
    // chain of guards — the same split CaptureRoutes.write/PlaygroundRoute.run use for the same wall.
    override suspend fun run(head: ManagedHead, prompt: String): PlaygroundOutcome {
        val path = configPath
            ?: return PlaygroundFailure("no topology file; this daemon has no configured provider")
        val topology = readTopology(path).getOrElse { return PlaygroundFailure(SafeFailureText.render(it)) }
        return resolveProvider(topology, head, prompt)
    }

    private suspend fun resolveProvider(topology: Topology, head: ManagedHead, prompt: String): PlaygroundOutcome {
        val headConfig = topology.heads[head.head.key]
            ?: return PlaygroundFailure("head '${head.head.key}' is not in the current topology")
        val provider = topology.providers[headConfig.provider]
            ?: return PlaygroundFailure("provider '${headConfig.provider}' is not declared")
        return resolveCredentials(provider, headConfig.pinnedModel, head, prompt)
    }

    private suspend fun resolveCredentials(
        provider: ProviderConfig,
        model: String,
        head: ManagedHead,
        prompt: String,
    ): PlaygroundOutcome {
        val creds = Cancellables.runCatchingCancellable { head.auth.credentials() }
            .getOrElse { return PlaygroundFailure("reading credentials failed: ${SafeFailureText.render(it)}") }
        return when {
            creds == null -> PlaygroundFailure("head '${head.head.key}' has no credential configured")
            creds is Credentials.ClientForwarded -> {
                val why = "head '${head.head.key}' forwards the caller's own auth; playground has none to send"
                PlaygroundFailure(why)
            }
            else -> call(provider, model, creds, prompt)
        }
    }

    private suspend fun call(
        provider: ProviderConfig,
        model: String,
        creds: Credentials,
        prompt: String,
    ): PlaygroundOutcome {
        val url = upstreamUrl(provider)
        val bodyElement = requestBody(provider.dialect, model, prompt)
        val auth = authHeaders(creds)
        val allHeaders = provider.staticHeaders + auth
        val sent = Cancellables.runCatchingCancellable {
            client.post(url) {
                headers { allHeaders.forEach { (name, value) -> append(name, value) } }
                contentType(ContentType.Application.Json)
                setBody(bodyElement.toString())
            }
        }.getOrElse { return PlaygroundFailure("upstream call to $url failed: ${SafeFailureText.render(it)}") }
        val bodyText = sent.bodyAsText().take(MAX_RESPONSE_CHARS)
        val requestJson = buildJsonObject {
            put("url", url)
            put("method", "POST")
            putJsonObject("headers") { redactedHeaders(allHeaders, auth).forEach { (k, v) -> put(k, v) } }
            put("body", bodyElement)
        }
        val responseJson = buildJsonObject {
            put("status", sent.status.value)
            put("body", parseOrRaw(bodyText))
        }
        return PlaygroundResult(requestJson, responseJson)
    }

    private fun readTopology(path: Path): Result<Topology> = Cancellables.runCatchingCancellable {
        TopologyLoader.parse(Files.readString(path))
    }

    private fun upstreamUrl(provider: ProviderConfig): String = when (provider.dialect) {
        Dialect.ANTHROPIC_PASSTHROUGH -> "${provider.baseUrl}/v1/messages"
        Dialect.OPENAI_RESPONSES -> "${provider.baseUrl}/responses"
        Dialect.OPENAI_CHAT -> "${provider.baseUrl}/chat/completions"
    }

    private fun requestBody(dialect: Dialect, model: String, prompt: String): JsonElement = when (dialect) {
        Dialect.ANTHROPIC_PASSTHROUGH -> buildJsonObject {
            put("model", model)
            put("max_tokens", PLAYGROUND_MAX_TOKENS)
            putJsonArray("messages") { add(userMessage(prompt)) }
        }
        Dialect.OPENAI_RESPONSES -> buildJsonObject {
            put("model", model)
            put("input", prompt)
        }
        Dialect.OPENAI_CHAT -> buildJsonObject {
            put("model", model)
            putJsonArray("messages") { add(userMessage(prompt)) }
        }
    }

    private fun userMessage(prompt: String): JsonElement = buildJsonObject {
        put("role", "user")
        put("content", prompt)
    }

    private fun authHeaders(creds: Credentials): Map<String, String> = when (creds) {
        is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
        is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
        Credentials.ClientForwarded -> emptyMap()
    }

    private fun redactedHeaders(all: Map<String, String>, auth: Map<String, String>): Map<String, String> =
        all.mapValues { (name, value) -> if (name in auth) HeaderRedaction.REDACTED else value }

    private fun parseOrRaw(text: String): JsonElement =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text) }.getOrElse {
            buildJsonObject { put("raw", text) }
        }
}
