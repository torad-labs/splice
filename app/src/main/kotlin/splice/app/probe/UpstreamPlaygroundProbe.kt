// NEW: V4-133, FEATURES.md §5/§6 — the :app-side implementation of
// splice.diagnostics.playground.PlaygroundProbe: POST /api/playground's ONE independent upstream call.
//
// NAMED UpstreamPlaygroundProbe, NOT PlaygroundProbe: the fun interface it implements already owns
// that name in splice.control.api, and a same-named concrete class one package over is exactly the
// self-referential-import confusion a route implemented beside its own port would invite.
//
// THE TURN'S OWN REQUEST (V4-444): the body is what the head's own provider builds (Provider.buildTurn)
// from the one-message turn Claude Code would send with this prompt, posted to the provider's own URL
// under the provider's and the turn's headers, composed by the transport's own UpstreamHeaders. Until
// marlin's check of 0e22c018b this file hand-built each dialect's body, a second copy of the request
// shape that had drifted from the turn path: ChatGPT refused every send with 400 "Input must be a list".
// One builder means the Playground succeeds and fails exactly where a turn would.
//
// ONE SHOT, NO PIPELINE — see PlaygroundRoute.kt's header for the full reason (never recorded means the
// whole turn pipeline, not only the console). This bypasses :daemon-head's TurnDriver: no retry loop, no
// account rotation, no perf, trace or economics write. The builder writes nothing for a turn with no tools
// (code mode engages only on one that has them). The credential is the head's own (PlaygroundHead.auth,
// which is ManagedHead.auth, the same instance the head's provider was built with).
package splice.app.probe

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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.parse.AnthropicParse
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.diagnostics.playground.PlaygroundFailure
import splice.diagnostics.playground.PlaygroundHead
import splice.diagnostics.playground.PlaygroundOutcome
import splice.diagnostics.playground.PlaygroundProbe
import splice.diagnostics.playground.PlaygroundResult
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.transport.HeaderRedaction
import splice.upstream.transport.UpstreamHeaders

// why: matches UpstreamTransport's own connect budget — a playground probe is not a special client.
private const val CONNECT_TIMEOUT_MS = 10_000L

// why: one interactive turn, not a conversation, so a full minute is generous rather than tight — long
// enough for a slow vendor to finish its stream without holding the route open indefinitely.
private const val REQUEST_TIMEOUT_MS = 60_000L

// why: a playground reply is a debugging echo of ONE turn, not a served conversation — capped well
// under a real turn's budget so one slow or huge vendor response cannot hold the route open forever.
private const val MAX_RESPONSE_CHARS = 200_000

// why: a minimal probe still needs an answer longer than a one-line ack to be useful to read.
private const val PLAYGROUND_MAX_TOKENS = 1024

/** The one-message turn Claude Code sends with a prompt: what the head's provider builds the upstream request from. */
internal object PlaygroundTurn {
    /** The turn with [prompt] on [model], as the JSON text Claude Code posts. */
    fun of(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", PLAYGROUND_MAX_TOKENS)
        put("stream", true)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    }
                }
            }
        }
    }.toString()
}

internal class UpstreamPlaygroundProbe(
    private val providers: PlaygroundProviders,
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
    /** [model] null runs the head's pinned model. A named one is sent as written, and an id its provider
     *  does not serve comes back as the provider's own answer, shown like any other. */
    override suspend fun run(head: PlaygroundHead, prompt: String, model: String?): PlaygroundOutcome {
        val provider = providers[head.key]
            ?: return PlaygroundFailure("head '${head.key}' has no running provider to send through")
        val creds = Cancellables.runCatchingCancellable { head.auth.credentials() }
            .getOrElse { return PlaygroundFailure("reading credentials failed: ${SafeFailureText.render(it)}") }
        return when {
            creds == null -> PlaygroundFailure("head '${head.key}' has no credential configured")
            creds is Credentials.ClientForwarded -> {
                val why = "head '${head.key}' forwards the caller's own auth; playground has none to send"
                PlaygroundFailure(why)
            }
            else -> build(provider, creds, model ?: provider.pinnedModel, prompt)
        }
    }

    private suspend fun build(
        provider: Provider,
        creds: Credentials,
        model: String,
        prompt: String,
    ): PlaygroundOutcome {
        val turn = Cancellables.runCatchingCancellable {
            provider.buildTurn(AnthropicParse.parseAnthropicBody(PlaygroundTurn.of(model, prompt)), false, null)
        }.getOrElse { return PlaygroundFailure("building the request failed: ${SafeFailureText.render(it)}") }
        return send(provider, creds, turn)
    }

    private suspend fun send(provider: Provider, creds: Credentials, turn: BuiltTurn): PlaygroundOutcome {
        val url = provider.upstreamUrl
        val headers = UpstreamHeaders.compose(creds, provider.extraHeaders(creds) + turn.extraHeaders)
        val sent = Cancellables.runCatchingCancellable {
            client.post(url) {
                headers { headers.forEach { (name, value) -> append(name, value) } }
                contentType(ContentType.Application.Json)
                setBody(turn.requestBody.toString())
            }
        }.getOrElse { return PlaygroundFailure("upstream call to $url failed: ${SafeFailureText.render(it)}") }
        val bodyText = sent.bodyAsText().take(MAX_RESPONSE_CHARS)
        val requestJson = buildJsonObject {
            put("url", url)
            put("method", "POST")
            putJsonObject("headers") { redacted(headers, creds).forEach { (k, v) -> put(k, v) } }
            put("body", turn.requestBody)
        }
        val responseJson = buildJsonObject {
            put("status", sent.status.value)
            put("body", parseOrRaw(bodyText))
        }
        return PlaygroundResult(requestJson, responseJson)
    }

    /** Every header that may carry a credential, by the trace's own rule, and the credential's own header by
     *  name whatever it is called, read as redacted. */
    private fun redacted(headers: Map<String, String>, creds: Credentials): Map<String, String> {
        val own = CredentialKey.headers(creds, emptyMap()).keys.map(String::lowercase).toSet()
        return HeaderRedaction.redact(headers).mapValues { (name, value) ->
            if (name.lowercase() in own) HeaderRedaction.REDACTED else value
        }
    }

    private fun parseOrRaw(text: String): JsonElement =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text) }.getOrElse {
            buildJsonObject { put("raw", text) }
        }
}
