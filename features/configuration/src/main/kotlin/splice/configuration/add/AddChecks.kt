// NEW: v0.4.0 FEATURES.md §1 — the checks `splice add` runs BEFORE it writes anything — the
// candidate TOML parses, the credential is present, the base URL answers, the chosen models are
// listed where the dialect publishes a list — and the ONE optional live turn the operator can ask
// for. Network goes through one seam so the command is tested without a socket.
package splice.configuration.add

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.accounts.status.CredentialPresence
import splice.core.model.ModelEntry
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.oauth.StoredCredential
import splice.topology.TopologyLoader
import java.nio.file.Path

private const val HTTP_OK = 200
private const val LISTED_SHOWN = 10
private const val LIVE_MAX_TOKENS = 8
private const val MODELS_CHECK = "models"

/** Where the three runtimes v0.4.0 names serve their OpenAI-compatible API by default: Ollama, LM Studio, vLLM. */
private val LOCAL_RUNTIME_ENDPOINTS = listOf(
    "http://localhost:11434/v1",
    "http://localhost:1234/v1",
    "http://localhost:8000/v1",
)

internal data class AddCheck(val name: String, val ok: Boolean, val detail: String)

/** What GET /models yielded: the dialect has no list, the endpoint could not serve it, or the ids. */
internal sealed class ListedModels {
    data object Absent : ListedModels()
    data class Unreadable(val detail: String) : ListedModels()
    data class Listed(val ids: List<String>, val windows: Map<String, Long> = emptyMap()) : ListedModels()
}

/** [output] takes the one line an unreadable credential file raises (CredentialPresence, DR-70). */
internal class AddChecks(output: TerminalOutput, private val http: AddHttp = JdkAddHttp()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val presence = CredentialPresence(output)
    private val credentialFile = AddCredentialFile(json)
    private val credentials = StoredCredential(json)

    /** Every check an add runs before it writes, in the order they print; [live] adds the one turn.
     *  V4-220: one list for the CLI and the console, so neither can save past a check the other runs. */
    fun all(c: AddCandidate, live: Boolean, env: EnvReader): List<AddCheck> {
        val local = c.resolved.provider.authKind == API_KEY && !c.resolved.provider.requiresKey
        val keyEnv = c.provider.auth.effectiveApiKeyEnv(c.key)
        // Local runtimes need no operator key. Checks present only the non-secret placeholder,
        // never a real key the operator may have stored under the same env name.
        val checkEnv = if (local) {
            EnvReader { name -> if (name == keyEnv) RUNTIME_KEY_PLACEHOLDER else env(name) }
        } else {
            env
        }
        val listed = listedModels(c.provider, c.key, checkEnv)
        return listOf(
            credential(c, env),
            reachable(c.provider.baseUrl),
            modelsListed(c.models, listed, c.resolved.policy.listAuthoritative),
            modelWindows(c.provider.models, listed),
        ) + listOfNotNull(if (live) liveTurn(c.provider, c.key, c.models.first(), checkEnv) else null)
    }

    /** The well-known local runtime endpoints that answer a model list right now, in the order they are probed.
     *  A runtime the operator started elsewhere is not found; it still takes --base-url. */
    fun localEndpoints(): List<String> =
        LOCAL_RUNTIME_ENDPOINTS.filter { http("GET", "$it/models", null, null)?.status == HTTP_OK }

    /** The candidate file must parse as a topology before anyone is asked to sign in. */
    fun parses(text: String): Result<Topology> = Cancellables.runCatchingCancellable { TopologyLoader.parse(text) }

    /** A local runtime does not ask the operator for a key; save stores its non-secret placeholder. */
    fun credential(c: AddCandidate, env: EnvReader): AddCheck =
        if (c.resolved.provider.authKind == API_KEY && !c.resolved.provider.requiresKey) {
            AddCheck("credential", true, "local runtime needs no operator key")
        } else {
            credential(c.key, c.provider, env)
        }

    /** Present AND usable: an OAuth file must carry token material the daemon could serve or refresh. */
    fun credential(key: String, provider: ProviderConfig, env: EnvReader): AddCheck {
        val kind = provider.auth.kind
        val problem = when {
            kind == AuthKind.Client.wire -> null
            !presence.configured(key, provider, env) -> "no credential for '$key' ($kind)"
            AuthKindRegistry.isOAuth(kind) -> oauthPath(provider)?.let { credentialFile.problem(it, kind) }
            else -> null
        }
        val oauth = AuthKindRegistry.isOAuth(kind)
        val usable = if (problem == null && oauth) "usable (unexpired or refreshable)" else "present"
        return AddCheck("credential", problem == null, problem ?: usable)
    }

    /** [StoredCredential] owns where a kind keeps its file (2026-09-22), so this check and
     *  `splice models`' bearer lookup cannot drift into two answers. */
    private fun oauthPath(provider: ProviderConfig): Path? = credentials.pathFor(provider)

    /** Any HTTP answer counts — an unauthenticated 401 still proves the endpoint is there. V4-266: so
     *  the line says that, not the status, which beside a green tick read as a failure (a 403). */
    fun reachable(baseUrl: String): AddCheck {
        val reply = http("GET", baseUrl, null, null)
        val detail = if (reply != null) "reachable at $baseUrl" else "nothing answers at $baseUrl"
        return AddCheck("base url", reply != null, detail)
    }

    /** The endpoint's model list where the dialect publishes one (openai-chat: GET /models). A list the
     *  dialect has but the endpoint cannot serve is [ListedModels.Unreadable], never "trusted". */
    fun listedModels(provider: ProviderConfig, key: String, env: EnvReader): ListedModels {
        if (provider.dialect != Dialect.OPENAI_CHAT) return ListedModels.Absent
        val url = provider.baseUrl.trimEnd('/') + "/models"
        val reply = http("GET", url, apiKey(provider, key, env), null)
        return when {
            reply == null -> ListedModels.Unreadable("nothing answers at $url")
            reply.status != HTTP_OK -> ListedModels.Unreadable("HTTP ${reply.status} from $url")
            else -> parsedList(reply.body, url)
        }
    }

    private fun parsedList(body: String, url: String): ListedModels = Cancellables.runCatchingCancellable {
        val entries = (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty()
        val windows = entries.mapNotNull { element ->
            val row = element.jsonObject
            val id = JsonScalars.str(row, "id") ?: return@mapNotNull null
            val window = (JsonScalars.long(row, "context_length") ?: JsonScalars.long(row, "context_window"))
                ?.takeIf { it > 0 } ?: return@mapNotNull null
            id to window
        }.toMap()
        ListedModels.Listed(entries.mapNotNull { JsonScalars.str(it.jsonObject, "id") }, windows)
    }.fold(
        onSuccess = { it },
        onFailure = { ListedModels.Unreadable("$url did not answer with a model list") },
    )

    /** [authoritative] false is a server that answers any model id: its list still has to ANSWER
     *  (an unreadable one fails as before), but an unlisted row is trusted and said to be. */
    fun modelsListed(models: List<String>, listed: ListedModels, authoritative: Boolean = true): AddCheck =
        when (listed) {
            // V4-267: in the user's terms, not the dialect's: where the rows came from and why nothing checked them.
            ListedModels.Absent -> {
                val count = if (models.size == 1) "1 model" else "${models.size} models"
                val why = "this provider publishes no list to check them against"
                AddCheck(MODELS_CHECK, true, "$count from splice's catalog; $why")
            }
            is ListedModels.Unreadable ->
                AddCheck(MODELS_CHECK, false, "${listed.detail}; the model list could not be checked")
            is ListedModels.Listed -> {
                val missing = models.filterNot { it in listed.ids }
                val shown = listed.ids.take(LISTED_SHOWN)
                when {
                    missing.isEmpty() ->
                        AddCheck(MODELS_CHECK, true, "all ${models.size} row(s) listed by the endpoint")
                    authoritative ->
                        AddCheck(MODELS_CHECK, false, "not listed by the endpoint: $missing (it lists $shown)")
                    else ->
                        AddCheck(MODELS_CHECK, true, "$missing trusted: the server answers any model id (lists $shown)")
                }
            }
        }

    /** Check advertised limits and name every declared row whose limit could not be checked. */
    fun modelWindows(models: List<ModelEntry>, listed: ListedModels): AddCheck {
        val windows = (listed as? ListedModels.Listed)?.windows.orEmpty()
        val oversized = models.any { row -> windows[row.id]?.let { row.contextWindow > it } == true }
        val detail = models.joinToString("; ") { row ->
            val served = windows[row.id]
            when {
                served == null -> "${row.id} unchecked: provider lists no window size"
                row.contextWindow > served -> "${row.id} declares ${row.contextWindow}, provider serves $served"
                else -> "${row.id} fits: declares ${row.contextWindow}, provider serves $served"
            }
        }.ifEmpty { "no declared model windows to check" }
        return AddCheck("windows", !oversized, detail)
    }

    /** The console's direct check supports openai-chat with a splice-held key only.
     *  The CLI instead checks the installed command after saving, through AddPorts.liveTurn. */
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
