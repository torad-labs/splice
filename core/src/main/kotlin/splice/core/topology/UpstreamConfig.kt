// NEW: server mode (docs/specs/server-mode.md, F4). A provider that is several servers: a team's box is often two or
// three GPU machines running the same model behind the same API, and `[[providers.<p>.upstreams]]` lists them in place
// of the provider's one `base_url`. Each is asked `/health` every `health_seconds`, a turn goes to a healthy one, and
// what the decode accepts here but no pool could serve is a boot finding beside every other.
package splice.core.topology

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/** One server of a provider's pool: the [name] an operator and a person's preference call it by, and its API root,
 *  as a provider's `base_url` spells one (`http://10.0.0.21:8080/v1`). */
@Serializable
public data class UpstreamConfig(val name: String, val url: String) {
    /** [url] without a trailing slash: what a request's path is appended to. */
    val base: String get() = url.trimEnd('/')

    /** Where this server says whether it can take a turn: `/health` beside its API root, which is where llama-server
     *  and vLLM serve it (`http://10.0.0.21:8080/v1` is asked at `http://10.0.0.21:8080/health`). */
    val healthUrl: String get() = base.removeSuffix(API_ROOT) + HEALTH_PATH
}

/** What `[[providers.<p>.upstreams]]` and `health_seconds` hold that no pool could serve. */
internal class UpstreamChecks(private val providers: Map<String, ProviderConfig>) {
    private val nameShape = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,$NAME_MAX}$""")

    fun findings(): List<TopologyFinding> = providers.flatMap { (key, provider) -> of(key, provider) }

    private fun of(key: String, provider: ProviderConfig): List<TopologyFinding> {
        val table = "providers.$key"
        val listed = provider.upstreams
        if (listed.isEmpty()) {
            val none = "set base_url, or list this provider's servers under [[$table.upstreams]]"
            return listOfNotNull(TopologyFinding("$table.base_url", none).takeIf { provider.baseUrl.isBlank() })
        }
        val names = listed.map { it.name }
        val whole = listOfNotNull(
            "upstreams take the place of base_url: remove base_url, which names one server, or the upstreams"
                .takeIf { provider.baseUrl.isNotBlank() && provider.baseUrl != listed.first().url },
            "upstreams are for a provider whose dialect is \"openai-chat\", the API a self-hosted server speaks"
                .takeIf { provider.dialect != Dialect.OPENAI_CHAT },
            "two upstreams share a name; a turn and a person's preference name each by its own"
                .takeIf { names.distinct().size != names.size },
        ).map { TopologyFinding("$table.upstreams", it) }
        val each = listed.indices.flatMap { at -> entry("$table.upstreams[$at]", listed[at]) }
        val health = TopologyFinding("$table.health_seconds", "must be 1 or more: the seconds between health checks")
            .takeIf { provider.healthSeconds < 1 }
        return whole + each + listOfNotNull(health)
    }

    private fun entry(path: String, upstream: UpstreamConfig): List<TopologyFinding> = listOfNotNull(
        TopologyFinding("$path.name", "must be a letter or digit, then letters, digits, '.', '_' or '-'")
            .takeUnless { nameShape.matches(upstream.name) },
        TopologyFinding("$path.url", "must be the server's API root, as in \"http://10.0.0.21:8080/v1\"")
            .takeUnless { reachable(upstream.url) },
    )

    /** Whether [url] is an http or https address with a host, which is all a request can be sent to. */
    private fun reachable(url: String): Boolean {
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        return (parsed.scheme == "http" || parsed.scheme == "https") && !parsed.host.isNullOrBlank()
    }
}

/** How often a pool asks each of its servers `/health` when its provider sets no `health_seconds`. */
public const val DEFAULT_HEALTH_SECONDS: Int = 5

// The API root an OpenAI-compatible server's routes sit under, and where its health route sits beside it.
private const val API_ROOT = "/v1"
private const val HEALTH_PATH = "/health"

// why: an upstream's name is typed after `--upstream` and read in a list; 64 characters hold any such name.
private const val NAME_MAX = 63
