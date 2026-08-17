// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: a failed connect logs and returns null (SSE
// fallback) without ever registering a connection; a fresh generation is minted only once a socket
// is actually attempted.
package splice.dialect.responses

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

// Bounded so a stalled consumer cannot buffer unboundedly; the turn watchdog fires long
// before a healthy translator falls 1024 events behind. Overflow = kill (InboxListener).
private const val INBOX_CAPACITY = 1024

/** Opens ONE upstream socket for a key: mint its generation, wire the inbox and its listener, and
 *  call the connector. The registry dance (race re-check, LRU register, idle eviction) is
 *  [WsConnectionPool]'s job — this is only ever the socket-opening half of "connect". */
internal class WsConnectionFactory(
    private val connector: WsConnector,
    private val log: LogSink,
    private val logKeys: WsLogKeys,
) {
    private val generations = AtomicLong(0)

    internal suspend fun connect(key: String, headers: Map<String, String>, wssUrl: String): WsConnection? {
        val generation = generations.incrementAndGet()
        val inbox = Channel<JsonObject>(INBOX_CAPACITY)
        val holder = arrayOfNulls<WsConnection>(1)
        val listener = InboxListener(
            inbox,
            log,
            terminalSeen = { holder[0]?.terminalSeen?.get() == true },
        ) { holder[0]?.kill() }
        val socket = Cancellables.runCatchingCancellable {
            connector(URI.create(wssUrl), headers, listener)
        }.getOrElse { e ->
            log(
                "[ws] ${logKeys.logKey(key)} connect failed (${e::class.simpleName}: " +
                    "${e.message?.take(ERR_SNIPPET)}) — SSE\n",
            )
            return null
        }
        val conn = WsConnection(socket, inbox, generation, log)
        holder[0] = conn
        return conn
    }
}
