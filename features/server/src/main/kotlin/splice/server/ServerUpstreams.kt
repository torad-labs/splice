// NEW: server mode (docs/specs/server-mode.md, F4). The servers behind this machine's providers, as server mode asks
// about them: the operator's list (`GET /api/server/upstreams`), the names a person may be set to prefer, and what a
// key holder is told when a command's provider has no server up. The pools themselves are the daemon's: a head sends
// each turn to one of its provider's servers, and server mode only reads them.
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.pool.UpstreamPool

/** The daemon's pools: one for each provider that lists `[[providers.<p>.upstreams]]` and has a head. A daemon whose
 *  providers are each one server has none, which is what the defaults answer. */
public interface HeadUpstreams {
    /** Every pool, in the order their providers' heads were built. */
    public fun all(): List<UpstreamPool> = emptyList()

    /** The pool the head [head] sends its turns to, by its key in splice.toml; null when its provider is one server. */
    public fun of(head: String): UpstreamPool? = null
}

public class UpstreamApi(private val upstreams: HeadUpstreams) {

    /** Every upstream as it reads now: `{"upstreams": [{"provider", "name", "url", "healthy", "inflight"}]}`. */
    public fun list(): ServerAnswer {
        val rows = upstreams.all().flatMap { it.readings() }
        val body = buildJsonObject {
            put(
                "upstreams",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("provider", row.provider)
                                put("name", row.name)
                                put("url", row.url)
                                put("healthy", row.healthy)
                                put("inflight", row.inflight)
                            },
                        )
                    }
                },
            )
        }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    /** The names a person can be set to prefer: every upstream of every pool, once each. */
    internal fun names(): List<String> = upstreams.all().flatMap { pool -> pool.upstreams.map { it.name } }.distinct()

    /** Why a turn for the head [head] cannot be served now, or null while its provider has a server up or is one
     *  server: the sentence names the provider and each of its upstreams, which the person passes on to the
     *  operator. */
    internal fun noneUp(head: String): String? {
        val pool = upstreams.of(head)?.takeUnless { it.up } ?: return null
        val names = pool.upstreams.joinToString(", ") { it.name }
        return "no upstream of ${pool.provider} is up ($names): none passed its last health check; " +
            "a turn is served again as soon as one does"
    }
}
