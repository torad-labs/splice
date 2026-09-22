// NEW: v0.4.0 FEATURES.md §1 — the checks `splice add` runs BEFORE it writes anything — the
// candidate TOML parses, the credential is present, the base URL answers, the chosen models are
// listed where the dialect publishes a list — and the ONE optional live turn the operator can ask
// for. Network goes through one seam so the command is tested without a socket.
package splice.app.cli.add

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.app.auth.LoginIo
import splice.app.auth.StoredCredential
import splice.app.daemon.TopologyLoader
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.nio.file.Path

private const val HTTP_OK = 200
private const val LISTED_SHOWN = 10
private const val LIVE_MAX_TOKENS = 8
private const val MODELS_CHECK = "models"

internal data class AddCheck(val name: String, val ok: Boolean, val detail: String)

/** What GET /models yielded: the dialect has no list, the endpoint could not serve it, or the ids. */
internal sealed class ListedModels {
    data class Absent(val dialect: String) : ListedModels()
    data class Unreadable(val detail: String) : ListedModels()
    data class Listed(val ids: List<String>) : ListedModels()
}

internal class AddChecks(private val http: AddHttp = JdkAddHttp()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loginIo = LoginIo()
    private val credentialFile = AddCredentialFile(json)
    private val credentials = StoredCredential(json)

    /** The candidate file must parse as a topology before anyone is asked to sign in. */
    fun parses(text: String): Result<Topology> = Cancellables.runCatchingCancellable { TopologyLoader.parse(text) }

    /** Present AND usable: an OAuth file must carry token material the daemon could serve or refresh. */
    fun credential(key: String, provider: ProviderConfig, env: EnvReader): AddCheck {
        val kind = provider.auth.kind
        val problem = when {
            kind == AuthKind.Client.wire -> null
            !loginIo.credentialConfigured(key, provider, env) -> "no credential for '$key' ($kind)"
            AuthKindRegistry.isOAuth(kind) -> oauthPath(provider)?.let { credentialFile.problem(it, kind) }
            else -> null
        }
        return AddCheck("credential", problem == null, problem ?: "present")
    }

    /** [StoredCredential] owns where a kind keeps its file (2026-09-22), so this check and
     *  `splice models`' bearer lookup cannot drift into two answers. */
    private fun oauthPath(provider: ProviderConfig): Path? = credentials.pathFor(provider)

    /** Any HTTP answer counts — an unauthenticated 401 still proves the endpoint is there. */
    fun reachable(baseUrl: String): AddCheck {
        val reply = http("GET", baseUrl, null, null)
        val detail = reply?.let { "HTTP ${it.status} from $baseUrl" } ?: "nothing answers at $baseUrl"
        return AddCheck("base url", reply != null, detail)
    }

    /** The endpoint's model list where the dialect publishes one (openai-chat: GET /models). A list the
     *  dialect has but the endpoint cannot serve is [ListedModels.Unreadable], never "trusted". */
    fun listedModels(provider: ProviderConfig, key: String, env: EnvReader): ListedModels {
        if (provider.dialect != Dialect.OPENAI_CHAT) return ListedModels.Absent(provider.dialect.toString())
        val url = provider.baseUrl.trimEnd('/') + "/models"
        val reply = http("GET", url, apiKey(provider, key, env), null)
        return when {
            reply == null -> ListedModels.Unreadable("nothing answers at $url")
            reply.status != HTTP_OK -> ListedModels.Unreadable("HTTP ${reply.status} from $url")
            else -> parsedList(reply.body, url)
        }
    }

    private fun parsedList(body: String, url: String): ListedModels = Cancellables.runCatchingCancellable {
        (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it.jsonObject, "id") }
    }.fold(
        onSuccess = { ListedModels.Listed(it) },
        onFailure = { ListedModels.Unreadable("$url did not answer with a model list") },
    )

    fun modelsListed(models: List<String>, listed: ListedModels): AddCheck = when (listed) {
        is ListedModels.Absent ->
            AddCheck(MODELS_CHECK, true, "no model list on ${listed.dialect}; ${models.size} row(s) trusted")
        is ListedModels.Unreadable ->
            AddCheck(MODELS_CHECK, false, "${listed.detail} — the model list could not be checked")
        is ListedModels.Listed -> {
            val missing = models.filterNot { it in listed.ids }
            val shown = listed.ids.take(LISTED_SHOWN)
            if (missing.isEmpty()) {
                AddCheck(MODELS_CHECK, true, "all ${models.size} row(s) listed by the endpoint")
            } else {
                AddCheck(MODELS_CHECK, false, "not listed by the endpoint: $missing (it lists $shown)")
            }
        }
    }

    /** ONE short turn, only when asked. openai-chat with a splice-held key speaks plain HTTP here; every
     *  other pair is refused at parse time (AddPrepare), so reaching this branch without a bearer is a
     *  failed check, never a silent skip. */
    fun liveTurn(provider: ProviderConfig, key: String, model: String, env: EnvReader): AddCheck {
        val bearer = apiKey(provider, key, env)
        if (provider.dialect != Dialect.OPENAI_CHAT || bearer == null) {
            val why = "no live turn is possible for ${provider.dialect} without a splice-held key"
            return AddCheck("live turn", false, why)
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

    /** [StoredCredential] owns this too (2026-09-22), for the same reason [oauthPath] delegates. */
    private fun apiKey(provider: ProviderConfig, key: String, env: EnvReader): String? =
        credentials.apiKey(provider, key, env)
}
