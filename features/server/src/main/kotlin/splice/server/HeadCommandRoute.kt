// NEW: server mode (docs/specs/server-mode.md, F1 and F9), split from ServerListener (#791). A served command's own
// routes on the listener: `GET /<command>/launch`, what a laptop's launch of it needs, and `ANY /<command>/v1/...`,
// relayed to its head on loopback with this machine's turn key in place of the person's key, on a path spelled back
// from the segments the listener judged (RequestPaths). A `POST /<command>/v1/messages` is a turn: it takes one of its
// person's slots, or is refused, and leaves its row in the ledger however it ends. One from a laptop older than the
// minimum client is its head's to answer in the conversation, or is refused with the status the operator set, and
// takes no slot (ClientMinimum). The head sends its provider the caller's headers `forward_headers` names, never one
// holding a key this server issued (ProviderBound).
package splice.server

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.server.client.ClientMinimum
import splice.server.ledger.ROUTE_MESSAGES
import splice.server.path.RequestPaths
import splice.share.HEAD_API_ROOT
import splice.share.HEAD_LAUNCH_ROUTE
import splice.share.HeadTurn
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.SharedHead
import kotlin.time.Duration

/** What a command's routes are served with: the listener's [voice] and [catalog] of heads, its [admission] of turns
 *  and their [watched] relay, the [minimum] client, and the [keepalive] a streamed turn is kept alive at. */
internal data class HeadRouting(
    val voice: RefusalVoice,
    val catalog: ShareCatalog,
    val admission: TurnAdmission,
    val watched: WatchedRelay,
    val minimum: ClientMinimum,
    val keepalive: Duration?,
)

internal class HeadCommandRoute(private val parts: ServerParts, private val routing: HeadRouting, log: LogSink) {
    private val paths = RequestPaths()
    private val bound = ProviderBound(parts.keys, log)

    /** The path, after the command, of the one request that runs a turn on the Messages route. */
    private val turnPath = listOf(HEAD_API_ROOT, "messages")

    /** [path], whose first segment names a served command, for [key]'s person: its turns, or its launch. */
    suspend fun answer(call: ApplicationCall, key: ServerKey, path: List<String>) {
        val head = routing.catalog.named(call, path.first()) ?: return
        val rest = path.drop(1)
        when {
            rest.firstOrNull() == HEAD_API_ROOT -> relayed(call, key, head, rest)
            rest == listOf(HEAD_LAUNCH_ROUTE) && call.request.httpMethod == HttpMethod.Get ->
                routing.catalog.launch(call, head)
            else -> routing.voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${parts.heads.machine}")
        }
    }

    /** Relays [path] to [head] for [key]'s person: a turn through admission, an older laptop's turn and every other
     *  request with no slot. */
    private suspend fun relayed(call: ApplicationCall, key: ServerKey, head: SharedHead, path: List<String>) {
        val isTurn = call.request.httpMethod == HttpMethod.Post && path == turnPath
        val minimum = routing.minimum
        val told = if (isTurn) minimum.told(call, key.person) else null
        if (told != null && minimum.refused(call, routing.voice, told)) return
        if (!isTurn || told != null) {
            if (!head.running()) return routing.catalog.notRunning(call, head)
            val asked = headTurn(call, key, head, path, told?.let(minimum::notice).orEmpty())
            routing.watched.turn(call, slot = null) { parts.carriers.relay.turn(call, asked) }
            return
        }
        routing.admission.served(call, key, HeadCommand(head), ROUTE_MESSAGES) { turn ->
            val asked = headTurn(call, key, head, path, mapOf(parts.books.turns.named(turn)))
            parts.carriers.relay.turn(call, asked)
        }
    }

    /** [path] as [head] is handed it for [key]'s person, with [named] beside the upstream they prefer. */
    private fun headTurn(
        call: ApplicationCall,
        key: ServerKey,
        head: SharedHead,
        path: List<String>,
        named: Map<String, String>,
    ): HeadTurn {
        val preferred = parts.people.of(key.person).preferred
        val chosen = listOfNotNull(preferred?.let { PREFERRED_UPSTREAM_HEADER to it }).toMap()
        val held = bound.keyed(call, parts.upstreams.forwarded(head.key))
        return HeadTurn(head, paths.spell(path) + paths.query(call), chosen + named, routing.keepalive, held)
    }
}
