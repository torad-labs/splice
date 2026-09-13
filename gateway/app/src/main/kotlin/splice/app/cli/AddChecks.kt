// NEW (v0.4.0, FEATURES.md §1): the checks `splice add` runs BEFORE it writes anything — the
// candidate TOML parses, the credential is present, the base URL answers, the chosen models are
// listed where the dialect publishes a list — and the ONE optional live turn the operator can ask
// for. Network goes through one seam so the command is tested without a socket.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.app.LoginIo
import splice.app.TopologyLoader
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val HTTP_OK = 200
private const val PROBE_TIMEOUT_S = 10L
private const val LISTED_SHOWN = 10
private const val LIVE_MAX_TOKENS = 8

internal data class AddHttpReply(val status: Int, val body: String)

/** The one seam to the network: a request, or null when nothing answers. */
internal fun interface AddHttp {
    operator fun invoke(method: String, url: String, bearer: String?, body: String?): AddHttpReply?
}

internal class JdkAddHttp(private val client: HttpClient = HttpClient.newHttpClient()) : AddHttp {
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

internal data class AddCheck(val name: String, val ok: Boolean, val detail: String)

internal class AddChecks(private val http: AddHttp = JdkAddHttp()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loginIo = LoginIo()

    /** The candidate file must parse as a topology before anyone is asked to sign in. */
    fun parses(text: String): Result<Topology> = Cancellables.runCatchingCancellable { TopologyLoader.parse(text) }

    fun credential(key: String, provider: ProviderConfig, env: EnvReader): AddCheck {
        val ok = provider.auth.kind == AuthKind.Client.wire || loginIo.credentialConfigured(key, provider, env)
        val detail = if (ok) "present" else "no credential for '$key' (${provider.auth.kind})"
        return AddCheck("credential", ok, detail)
    }

    /** Any HTTP answer counts — an unauthenticated 401 still proves the endpoint is there. */
    fun reachable(baseUrl: String): AddCheck {
        val reply = http("GET", baseUrl, null, null)
        val detail = reply?.let { "HTTP ${it.status} from $baseUrl" } ?: "nothing answers at $baseUrl"
        return AddCheck("base url", reply != null, detail)
    }

    /** The endpoint's model list where the dialect has one (openai-chat: GET /models); null otherwise. */
    fun listedModels(provider: ProviderConfig, key: String, env: EnvReader): List<String>? {
        if (provider.dialect != Dialect.OPENAI_CHAT) return null
        val reply = http("GET", provider.baseUrl.trimEnd('/') + "/models", apiKey(provider, key, env), null)
            ?.takeIf { it.status == HTTP_OK }
            ?: return null
        return Cancellables.runCatchingCancellable {
            (json.parseToJsonElement(reply.body).jsonObject["data"] as? JsonArray).orEmpty()
                .mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.content }
        }.getOrNull()
    }

    fun modelsListed(models: List<String>, listed: List<String>?): AddCheck {
        val missing = models.filterNot { listed == null || it in listed }
        return when {
            listed == null -> AddCheck("models", true, "no model list on this endpoint; ${models.size} row(s) trusted")
            missing.isEmpty() -> AddCheck("models", true, "all ${models.size} row(s) listed by the endpoint")
            else -> AddCheck(
                "models",
                false,
                "not listed by the endpoint: $missing (it lists ${listed.take(LISTED_SHOWN)})",
            )
        }
    }

    /** ONE short turn, only when asked. openai-chat with a splice-held key speaks plain HTTP here;
     *  every other pair (browser OAuth, your own Claude login) is exercised by the first launch. */
    fun liveTurn(provider: ProviderConfig, key: String, model: String, env: EnvReader): AddCheck {
        val bearer = apiKey(provider, key, env)
        if (provider.dialect != Dialect.OPENAI_CHAT || bearer == null) {
            return AddCheck(
                "live turn",
                true,
                "skipped: ${provider.dialect} is exercised by the first launch (then splice doctor)",
            )
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", LIVE_MAX_TOKENS)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Reply with the single word pong.")
                        },
                    )
                },
            )
        }
        val reply = http("POST", provider.baseUrl.trimEnd('/') + "/chat/completions", bearer, body.toString())
        val detail = reply?.let { "HTTP ${it.status} (${it.body.length} bytes)" } ?: "no answer from the endpoint"
        return AddCheck("live turn", reply?.status == HTTP_OK, detail)
    }

    private fun apiKey(provider: ProviderConfig, key: String, env: EnvReader): String? {
        if (provider.auth.kind != "api-key") return null
        val envVar = provider.auth.effectiveApiKeyEnv(key)
        return env(envVar)?.takeIf { it.isNotBlank() } ?: KeyStore(KeyStorePath.defaultPath(env)).read(envVar)
    }
}
