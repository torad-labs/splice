// NEW: 2026-09-22 — ask ONE provider what it serves: resolve the list URL, present the credential
// that provider authenticates with, and hand the body to the pure parser.
//
// THE HEADERS ARE THE DIALECT'S, NOT A VENDOR'S. An anthropic-passthrough endpoint wants
// `x-api-key` and a version header; an openai-chat one wants a bearer. Both are sent where they
// apply rather than being switched on a provider KEY, because a per-vendor table is the hand-
// authored list this feature exists to retire. A vendor whose version header differs already
// declares it in `extra_headers`, and that declaration wins.
package splice.app.cli.models

import splice.app.auth.StoredCredential
import splice.core.model.UpstreamRoster
import splice.core.model.UpstreamRosterParser
import splice.core.model.UpstreamRosterUrl
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.wire.HttpStatus

// 200 stays file-local: HttpStatus deliberately excludes it (that file's "what is not here" note),
// and every other OK check in :app spells it this way. 401/403 come from there — they are exactly
// the declarations kt-http-status-single-source exists to keep from drifting into copies.
private const val HTTP_OK = 200

/** The Anthropic wire requires a version header on every request; a provider that declares its own
 *  in `extra_headers` overrides this default, which is the same precedence a turn uses. */
private const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
private const val ANTHROPIC_VERSION_DEFAULT = "2023-06-01"

/** One provider's answer, with the URL that produced it so the operator can re-run it by hand. */
internal data class ProbedProvider(
    val key: String,
    val provider: ProviderConfig,
    val url: String?,
    val roster: UpstreamRoster,
)

internal class ModelsProbe(
    private val http: ModelsHttp = JdkModelsHttp(),
    private val credentials: StoredCredential = StoredCredential(),
    private val parser: UpstreamRosterParser = UpstreamRosterParser(),
) {

    fun probe(key: String, provider: ProviderConfig, env: EnvReader): ProbedProvider {
        val url = UpstreamRosterUrl.of(provider.dialect, provider.baseUrl, provider.modelsUrl)
            ?: return ProbedProvider(key, provider, null, UpstreamRoster.Unpublished(UpstreamRosterUrl.RESPONSES_HAS_NO_LIST))
        if (provider.auth.kind == AuthKind.Client.wire) {
            return ProbedProvider(
                key,
                provider,
                url,
                UpstreamRoster.Unpublished(
                    "this provider forwards your own Claude login, so splice holds no credential to ask $url with",
                ),
            )
        }
        val reply = http(url, headers(provider, key, env))
        return ProbedProvider(key, provider, url, read(reply, url, provider, key))
    }

    /** A 401 is the one status worth naming on its own: it is almost always an expired stored
     *  credential, and the fix is a sign-in rather than anything about the model list. */
    private fun read(reply: ModelsReply?, url: String, provider: ProviderConfig, key: String): UpstreamRoster = when {
        // A local runtime that is not running is not a configuration fault — it is a process the
        // operator starts when they want it, and failing the verb over it would make `splice models`
        // red on any box where one of three local packs is up.
        reply == null && provider.isLocal ->
            UpstreamRoster.Unpublished("nothing answers at $url — this local runtime is not running")
        reply == null -> UpstreamRoster.Unreadable("nothing answers at $url")
        reply.status == HTTP_OK -> parser.parse(reply.body, url)
        reply.status == HttpStatus.UNAUTHORIZED || reply.status == HttpStatus.FORBIDDEN ->
            UpstreamRoster.Unreadable("$url refused the stored credential (HTTP ${reply.status}) — ${signIn(provider, key)}")
        else -> UpstreamRoster.Unreadable("HTTP ${reply.status} from $url")
    }

    private fun signIn(provider: ProviderConfig, key: String): String =
        if (provider.auth.kind == "api-key") {
            "set ${provider.auth.effectiveApiKeyEnv(key)} with `splice key set`"
        } else {
            "sign in again"
        }

    private fun headers(provider: ProviderConfig, key: String, env: EnvReader): Map<String, String> {
        val bearer = credentials.bearer(provider, key, env)
        val declared = provider.staticHeaders
        return buildMap {
            put("Accept", "application/json")
            if (provider.dialect == Dialect.ANTHROPIC_PASSTHROUGH) {
                put(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_DEFAULT)
                bearer?.let { put("x-api-key", it) }
            }
            bearer?.let { put("Authorization", "Bearer $it") }
            // The operator's own declarations last: a vendor that needs a different version header,
            // an account id or a beta flag says so once, in splice.toml, for turns AND for this.
            putAll(declared)
        }
    }
}
