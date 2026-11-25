// NEW: server mode (docs/specs/server-mode.md, F4). The servers behind this machine's providers, as server mode asks
// about them: the operator's list (`GET /api/server/upstreams`), the names a person may be set to prefer, and what a
// key holder is told when a command's provider has no server up. The pools themselves are the daemon's: a head sends
// each turn to one of its provider's servers, and server mode only reads them.
//
// An OpenAI-style client's request does not go through a head (F4, openai-route): the listener relays it to the
// provider's own server, so the daemon also says, for a head whose provider speaks openai-chat, where that server is
// and what a request to it carries (ChatServer).
package splice.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.pool.UpstreamPool
import splice.core.server.AnswerReading
import splice.core.server.ServedTokens
import splice.core.server.UpstreamRefusal
import splice.core.server.UpstreamState
import splice.core.server.UpstreamStatePolicy
import splice.core.server.UpstreamStateReading
import splice.core.topology.ServerState
import splice.share.StreamEdit
import splice.share.WholeEdit
import java.time.Clock

/** The daemon's pools: one for each provider that lists `[[providers.<p>.upstreams]]` and has a head. A daemon whose
 *  providers are each one server has none, which is what the defaults answer. */
public interface HeadUpstreams {
    /** Every pool, in the order their providers' heads were built. */
    public fun all(): List<UpstreamPool> = emptyList()

    /** The pool the head [head] sends its turns to, by its key in splice.toml; null when its provider is one server. */
    public fun of(head: String): UpstreamPool? = null

    /** The server-wide external signal, if configured, including for providers with no pool. */
    public fun availability(): UpstreamStateReading? = null

    /** The provider of the head [head] as an OpenAI-style client's requests reach it; null when that provider does
     *  not speak openai-chat, which is what the default answers. */
    public fun chat(head: String): ChatServer? = null

    /** The caller's headers that ride on to the provider of the head [head], by lowercase name: the ones that
     *  provider's `forward_headers` names, whatever dialect it speaks and whichever route the request came on. Every
     *  other header a caller sends stays on this machine. None by default. */
    public fun forwarded(head: String): List<String> = emptyList()

    /** What [tokens] on [model] cost at the rate card of the head [head]'s provider, in USD; null when the model has no
     *  card there, which is what the default answers. */
    public fun price(head: String, model: String, tokens: ServedTokens): Double? = null

    /** Each provider paid for by the hour (F10, `hourly_usd`), by its key in splice.toml, with what it costs an hour in
     *  USD. None by default. */
    public fun hourly(): Map<String, Double> = emptyMap()
}

/** A provider that speaks openai-chat, as the listener relays an OpenAI-style client's requests to it. */
public interface ChatServer {
    /** Its API root, without a trailing slash. A provider that is several servers is asked where its pool says
     *  instead ([HeadUpstreams.of]). */
    public val base: String

    /** What a request to it carries in place of the caller's key: the provider's configured headers and its own key
     *  as it stands now. Blocking: the key may be read from its file. */
    public fun headers(): Map<String, String>

    /** The chat request a client [sent], as the provider's server gets it. */
    public fun chat(sent: ByteArray): ChatAsk
}

/** One chat request on its way to a provider's server: its [body], what reads the events of its answer when that is a
 *  stream ([stream]; null for a request that asks for none), what reads it when it is not ([whole]), what both read of
 *  it for the ledger ([reading]), and the [model] the client asked for. */
public data class ChatAsk(
    val body: ByteArray,
    val stream: StreamEdit?,
    val whole: WholeEdit? = null,
    val reading: AnswerReading? = null,
    val model: String? = null,
)

public class UpstreamApi(
    private val upstreams: HeadUpstreams,
    state: ServerState? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val policy = state?.let(::UpstreamStatePolicy)

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
            upstreams.availability()?.let { put("state", stateRow(it)) }
        }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    internal fun externalRefusal(): UpstreamRefusal? =
        upstreams.availability()?.let { policy?.refusal(it.signal, clock.instant()) }

    private fun stateRow(reading: UpstreamStateReading): JsonObject = buildJsonObject {
        val signal = reading.signal
        put(
            "state",
            when (signal.state) {
                UpstreamState.Up -> "up"
                UpstreamState.Starting -> "starting"
                is UpstreamState.Off -> "off"
            },
        )
        put("until", (signal.state as? UpstreamState.Off)?.until?.toString())
        put("message", policy?.refusal(signal, clock.instant())?.message ?: signal.message)
        put("last_error", reading.lastError)
        put("last_checked", reading.lastChecked?.toString())
    }

    /** The names a person can be set to prefer: every upstream of every pool, once each. */
    internal fun names(): List<String> = upstreams.all().flatMap { pool -> pool.upstreams.map { it.name } }.distinct()

    /** The pool the head [head] sends its turns to; null when its provider is one server. */
    internal fun pool(head: String): UpstreamPool? = upstreams.of(head)

    /** The provider of the head [head] as an OpenAI-style client reaches it; null when it does not speak openai-chat. */
    internal fun chat(head: String): ChatServer? = upstreams.chat(head)

    /** The caller's headers the provider of the head [head] has named to ride on to it, by lowercase name. */
    internal fun forwarded(head: String): List<String> = upstreams.forwarded(head)

    /** What [tokens] on [model] cost at the rate card of the head [head]'s provider; null when there is no card. */
    internal fun price(head: String, model: String, tokens: ServedTokens): Double? =
        upstreams.price(head, model, tokens)

    /** Each provider paid for by the hour, with what it costs an hour in USD. */
    internal fun hourly(): Map<String, Double> = upstreams.hourly()

    /** How long a turn [noneUp] refused waits before it is worth sending again: the seconds until the provider's
     *  servers are next checked, sent as the refusal's `Retry-After` (#791); null while it has a server up or is one
     *  server with no pool. */
    internal fun recheck(head: String): Long? = upstreams.of(head)?.takeUnless { it.up }?.recheckSeconds

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
