// NEW: where Meta's gateway runs each Muse conversation, and when a conversation must leave that place (Oct 26, 2026).
//
// The gateway pins a conversation's requests to one place by x-meta-ai-gateway-session-id, and the
// conversation's prompt cache lives there. A request sent there while a generation is still running on it waits
// behind that generation. On Oct 26 a re-send 0.2 s after a 504 gateway_timeout, and the four requests after it,
// waited 18 minutes. After a turn the client aborted, the next warm turn waited over 30 s 6 times in 33, against 2
// to 3% after a normal turn. So a conversation leaves its route when the gateway gives up on a generation there, or
// when a turn ends while Meta may still be generating it, and every later request rides the new route, across
// daemon restarts.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.wire.HttpStatus
import splice.upstream.VendorRoute
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// why: the header Meta's gateway routes a request by.
private const val ROUTE_HEADER = "x-meta-ai-gateway-session-id"

// why: the error code Meta's gateway answers a 504 with when no stream started before its own timeout (Oct 26 trace).
private const val GATEWAY_TIMEOUT_CODE = "gateway_timeout"

// why: the moves live in the head's own state folder, beside nothing else of Muse's.
private const val MOVES_FILE = "muse-gateway-routes.json"

// why: what a head whose saved moves cannot be read does instead, said in each log line about it.
private const val FIRST_ROUTES = "every conversation starts on its first route"

// why: moves are rare (a gateway timeout, a turn left running), so a thousand covers months of conversations. The
// oldest move goes first, and its conversation returns to its first route long after what it left has ended.
private const val MOVES_KEPT = 1_000

// why: clear the UUID version nibble before setting version four on the digest-derived ID.
private const val UUID_VERSION_MASK = -0xF001L

// why: version four marks the stable gateway-routing UUID with the canonical version nibble.
private const val UUID_VERSION_FOUR = 0x4000L

// why: clear the upper UUID variant bits before setting the RFC variant on the second half.
private const val UUID_VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL

/** The route each conversation of one Muse head rides. A conversation starts on a route derived from its key, so it
 *  lands in the same place on every turn and after every restart with nothing stored. One that had to leave a route
 *  rides a new one from then on, and [stateDir] keeps that move for the next daemon. */
public class MuseGatewayRoutes(stateDir: Path?, private val log: LogSink) {
    private val file: Path? = stateDir?.resolve(MOVES_FILE)
    private val moves: LinkedHashMap<String, String> = load()

    /** The headers that route [conversation]'s next request. */
    public fun headers(conversation: String): Map<String, String> = mapOf(ROUTE_HEADER to current(conversation))

    /** The route of one turn of [conversation]: it follows that turn's requests and moves the conversation when one of
     *  them may still be running where it was sent. */
    public fun route(conversation: String): VendorRoute = TurnRoute(conversation)

    private fun current(conversation: String): String =
        synchronized(moves) { moves[conversation] } ?: firstRoute(conversation)

    /** [conversation] leaves [from] for a new route, unless it has already left it. */
    private fun leave(conversation: String, from: String, why: String) {
        synchronized(moves) {
            if (current(conversation) != from) return
            val to = UUID.randomUUID().toString()
            moves.remove(conversation)
            moves[conversation] = to
            while (moves.size > MOVES_KEPT) moves.remove(moves.keys.first())
            log("[muse] $conversation leaves gateway route $from for $to: $why")
            save()
        }
    }

    /** The gateway gave up waiting for the stream: a 504 whose error code is gateway_timeout. Nothing in it says the
     *  generation behind it stopped. */
    private fun gaveUp(status: Int, body: String): Boolean {
        if (status != HttpStatus.GATEWAY_TIMEOUT) return false
        val error = JsonScalars.objectOrNull(museJson, body)?.get("error") as? JsonObject
        return JsonScalars.str(error, "code") == GATEWAY_TIMEOUT_CODE
    }

    /** The route a conversation starts on: a UUID v4 layout over SHA-256 of its key, the same bytes the gateway pin has
     *  carried since Sep 28, so a running conversation stays where its cache is. */
    private fun firstRoute(conversation: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("splice-muse:$conversation".toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(bytes)
        val most = (buffer.long and UUID_VERSION_MASK) or UUID_VERSION_FOUR
        val least = (buffer.long and UUID_VARIANT_MASK) or Long.MIN_VALUE
        return UUID(most, least).toString()
    }

    private fun load(): LinkedHashMap<String, String> {
        val kept = LinkedHashMap<String, String>()
        val path = file?.takeIf { Files.exists(it) } ?: return kept
        val text = Cancellables.runCatchingCancellable { Files.readString(path) }
            .onFailure { log("[muse] could not read the gateway route moves at $path (${it.message}); $FIRST_ROUTES") }
            .getOrNull()
        val saved = text?.let { JsonScalars.objectOrNull(museJson, it) }
        if (text != null && saved == null) {
            log("[muse] the gateway route moves at $path are not a JSON object; $FIRST_ROUTES")
        }
        saved?.forEach { (conversation, route) ->
            JsonScalars.strIfString(route).takeIf(String::isNotBlank)?.let { kept[conversation] = it }
        }
        return kept
    }

    /** Called under the lock, so the file always holds the moves in the order they were made. */
    private fun save() {
        val path = file ?: return
        val json = buildJsonObject {
            moves.forEach { (conversation, route) -> put(conversation, JsonPrimitive(route)) }
        }
        val failed = Cancellables.runCatchingBestEffort { SecureFile.writeAtomic0600(path, JsonWire.string(json)) }
            .exceptionOrNull() ?: return
        log("[muse] could not save the gateway route moves at $path (${failed.message}); they hold until a restart")
    }

    /** One turn's requests. The one sent last counts as running at Meta until Meta answers it or ends its stream. */
    private inner class TurnRoute(private val conversation: String) : VendorRoute {
        private val running = AtomicReference<String?>(null)

        override fun sending(): Map<String, String> {
            val route = current(conversation)
            running.set(route)
            return mapOf(ROUTE_HEADER to route)
        }

        override fun answered(status: Int, body: String) {
            val route = running.getAndSet(null) ?: return
            if (gaveUp(status, body)) leave(conversation, route, "the gateway gave up on the generation there")
        }

        override fun finished() {
            running.set(null)
        }

        override fun turnEnded() {
            val route = running.getAndSet(null) ?: return
            leave(conversation, route, "the turn ended while Meta may still be generating it")
        }
    }
}
