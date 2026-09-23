// NEW: 2026-09-22 — ask ONE provider what it serves: resolve the list URL, present the credential
// that provider authenticates with, and hand the body to the pure parser.
//
// THE HEADERS ARE THE DIALECT'S, NOT A VENDOR'S. An anthropic-passthrough endpoint wants
// `x-api-key` and a version header; an openai-chat one wants a bearer. Both are sent where they
// apply rather than being switched on a provider KEY, because a per-vendor table is the hand-
// authored list this feature exists to retire. A vendor whose version header differs already
// declares it in `extra_headers`, and that declaration wins — the same precedence a turn uses.
//
// A REFUSAL NAMES WHICH REFUSAL IT IS. "The stored credential was rejected" and "splice held no
// credential to send" are different problems with different fixes, and a 401 alone cannot tell them
// apart — so whether a bearer was found is carried to the sentence rather than guessed at from it.
package splice.models.list

import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import java.time.Instant

/** The Anthropic wire requires a version header on every request; a provider that declares its own
 *  in `extra_headers` overrides this default. */
private const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
private const val ANTHROPIC_VERSION_DEFAULT = "2023-06-01"

/** One provider's answer, with the URL that produced it so the operator can re-run it by hand. */
internal data class ProbedProvider(
    val key: String,
    val provider: ProviderConfig,
    val url: String,
    val roster: UpstreamRoster,
)

internal class ModelsProbe(
    private val http: ModelsHttp = JdkModelsHttp(),
    private val credentials: ModelCredentialSource,
    private val parser: UpstreamRosterParser = UpstreamRosterParser(),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {

    fun probe(key: String, provider: ProviderConfig, env: EnvReader): ProbedProvider {
        val url = UpstreamRosterUrl.of(provider)
        if (provider.auth.kind == AuthKind.Client.wire) {
            val why = "this provider forwards your own Claude login, so splice holds no credential to ask $url with"
            return unpublished(key, provider, url, why)
        }
        val bearer = credentials.bearer(provider, key, env)
        val answer = http(url, headers(provider, bearer))
        return ProbedProvider(key, provider, url, read(answer, url, provider, key, bearer != null))
    }

    private fun unpublished(key: String, provider: ProviderConfig, url: String, why: String) =
        ProbedProvider(key, provider, url, UpstreamRoster.Unpublished(why))

    private fun read(
        answer: ModelsAnswer,
        url: String,
        provider: ProviderConfig,
        key: String,
        held: Boolean,
    ): UpstreamRoster = when (answer) {
        is ModelsAnswer.Answered -> served(answer, url, provider, key, held)
        // A local runtime that is not running is not a configuration fault — it is a process the
        // operator starts when they want it, and failing the verb over it would make `splice models`
        // red on any box where one of three local packs is up.
        is ModelsAnswer.NotListening ->
            if (provider.isLocal) {
                UpstreamRoster.Unpublished("nothing answers at $url — this local runtime is not running")
            } else {
                UpstreamRoster.Unreadable("nothing answers at $url")
            }
        // NOT routed through the local branch above: a URL that does not parse or a TLS failure is a
        // fault whether the endpoint is on this machine or not, and "not running" would send the
        // operator to restart a process that is already up.
        is ModelsAnswer.Failed -> UpstreamRoster.Unreadable("$url could not be asked — ${answer.detail}")
    }

    private fun served(
        answer: ModelsAnswer.Answered,
        url: String,
        provider: ProviderConfig,
        key: String,
        held: Boolean,
    ): UpstreamRoster = when {
        answer.status == HttpStatus.OK -> parser.parse(answer.body, url)
        answer.status != HttpStatus.UNAUTHORIZED && answer.status != HttpStatus.FORBIDDEN ->
            UpstreamRoster.Unreadable("HTTP ${answer.status} from $url")
        held -> UpstreamRoster.Unreadable(
            "$url refused the stored credential (HTTP ${answer.status}) — ${fix(provider, key)}",
        )
        else -> UpstreamRoster.Unreadable(
            "$url wants a credential and splice holds none for '$key' (${provider.auth.kind}) — ${fix(provider, key)}",
        )
    }

    /** The remedy for a refused stored credential. An OAuth token past its expiry is refreshed by the
     *  head on its first turn, so its refusal is not a login to redo; only a token that had not
     *  expired sends the operator to `splice login`. */
    private fun fix(provider: ProviderConfig, key: String): String {
        val expired = credentials.expiresAtMs(provider)?.takeIf { it <= clock() }
        return when {
            provider.auth.isApiKey -> "set ${provider.auth.effectiveApiKeyEnv(key)} with `splice key set`"
            expired != null ->
                "the stored token expired at ${Instant.ofEpochMilli(expired)}; the head refreshes it on its " +
                    "first turn, with no login needed"
            else -> "run `splice login $key`"
        }
    }

    private fun headers(provider: ProviderConfig, bearer: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (provider.dialect == Dialect.ANTHROPIC_PASSTHROUGH) {
            put(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_DEFAULT)
            bearer?.let { put("x-api-key", it) }
        }
        bearer?.let { put("Authorization", "Bearer $it") }
        // The operator's own declarations last: a vendor that needs a different version header, an
        // account id or a beta flag says so once, in splice.toml, for turns AND for this.
        putAll(provider.staticHeaders)
    }
}
