// NEW: where Meta's gateway runs each Muse conversation, and when a conversation must leave that place (Oct 26, 2026).
//
// Meta routes requests by their prompt_cache_key: "Requests that share a key route together, so a request is more
// likely to land on a backend that already holds its prefix" (dev.meta.ai/docs/prompt-caching). A conversation's own
// key is muse:<session>, and its prompt cache lives where that key lands. splice also sends
// x-meta-ai-gateway-session-id beside it, a header no Meta page names. Moving that header alone moved no request: on
// Oct 26, 10 of the 15 answers after a header-only move read 92 to 100% of the conversation from the cache it was
// meant to leave. So a route here is the key and the header together, and a conversation that leaves one carries a
// new key and a new header from then on. A request sent where a generation is still running waits behind that
// generation. On Oct 26 a re-send 0.2 s after a 504 gateway_timeout, and the four requests after it,
// waited 18 minutes. After a turn the client aborted, the next warm turn waited over 30 s 6 times in 33, against 2
// to 3% after a normal turn. So a request goes out on a new route whenever its conversation's previous request reached
// Meta whole and never finished: the gateway gave up on it, its stream broke and the turn sent it again or resumed it,
// or its turn ended while Meta was still generating it. A request whose connection failed before it was written whole
// left nothing running there, and moves nothing.
//
// A place can also go slow for a stretch while others run fast. After a warm turn waited over 60 s for Meta's headers,
// the session's next warm turn within 20 minutes was slow 34.7% of the time, against 1.3% after a fast one. So a
// conversation also leaves a route where Meta held a warm request's headers past the limit after it had all of the
// request, trading the stretch for one cold turn. A cold request keeps its route, where it just wrote its cache. Every
// later request rides the new route, across daemon restarts.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.wire.HttpStatus
import splice.upstream.RoundBody
import splice.upstream.VendorRoute
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// why: the header splice has sent beside the cache key since Sep 28 to pin a conversation's place at Meta.
private const val ROUTE_HEADER = "x-meta-ai-gateway-session-id"

// why: the field of a Responses request Meta routes by, and the one a moved conversation changes in each body.
private const val CACHE_KEY_FIELD = "prompt_cache_key"

// why: a moved conversation's key stays in Muse's namespace and is as long as a session's own: muse: and a UUID.
private const val MOVED_KEY_PREFIX = "muse:"

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

// why: the longest Meta may hold a warm request's headers after the request was written whole (the President, Oct 26).
// A warm turn waits 4 s for them at the median and 11 s at the 90th percentile; a slow route held them 30 to 180 s.
private const val HEADER_WAIT_LIMIT_MS = 60_000L

/** How long Meta may hold a warm request's headers after it has all of the request, [limitMs], before the conversation
 *  leaves that route, and the [clock] that times the wait. */
public data class MuseHeaderWait(
    val limitMs: Long = HEADER_WAIT_LIMIT_MS,
    val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
)

/** The route each conversation of one Muse head rides: the gateway header and the prompt_cache_key its requests carry.
 *  A conversation starts on its own key and a header derived from it, so it lands in the same place on every turn and
 *  after every restart with nothing stored. One that had to leave a route rides a new header and a key named after it
 *  from then on, and [stateDir] keeps that move for the next daemon. */
public class MuseGatewayRoutes(
    stateDir: Path?,
    private val log: LogSink,
    private val headerWait: MuseHeaderWait = MuseHeaderWait(),
) {
    private val file: Path? = stateDir?.resolve(MOVES_FILE)
    private val moves: LinkedHashMap<String, String> = load()

    /** The gateway header [conversation]'s next request carries. Its body carries that route's cache key beside it,
     *  which the turn's [route] writes there. */
    public fun headers(conversation: String): Map<String, String> = mapOf(ROUTE_HEADER to current(conversation))

    /** The route of one turn of [conversation]: it follows that turn's requests and moves the conversation when one of
     *  them may still be running where it was sent, or Meta held a warm one's headers past [headerWait]'s limit. */
    public fun route(conversation: String): VendorRoute = TurnRoute(conversation)

    private fun current(conversation: String): String = movedTo(conversation) ?: firstRoute(conversation)

    /** The route [conversation] moved to last; null while it rides its first. */
    private fun movedTo(conversation: String): String? = synchronized(moves) { moves[conversation] }

    /** The cache key of a conversation that moved to [route]. */
    private fun movedKey(route: String): String = "$MOVED_KEY_PREFIX$route"

    /** [conversation] leaves [from] for a new route, unless it has already left it. */
    private fun leave(conversation: String, from: String, why: String) {
        synchronized(moves) {
            if (current(conversation) != from) return
            val to = UUID.randomUUID().toString()
            moves.remove(conversation)
            moves[conversation] = to
            while (moves.size > MOVES_KEPT) moves.remove(moves.keys.first())
            log("[muse] $conversation leaves gateway route $from for $to, with cache key ${movedKey(to)}: $why")
            save()
        }
    }

    /** [round] carrying [key] as its cache key, or null when it already does. Every body is built with its
     *  conversation's own key, which Meta routes to the place a moved conversation left. */
    private fun rekeyed(round: RoundBody, key: String): RoundBody? {
        val request = when (round) {
            is RoundBody.Tree -> round.element as? JsonObject
            is RoundBody.Text -> JsonScalars.objectOrNull(museJson, round.text)
        }
        if (request == null || JsonScalars.str(request, CACHE_KEY_FIELD) == key) return null
        return RoundBody.Tree(JsonObject(request + (CACHE_KEY_FIELD to JsonPrimitive(key))))
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
            .onFailure { t ->
                val why = SafeFailureText.render(t)
                log("[muse] could not read the gateway route moves at $path ($why); $FIRST_ROUTES")
            }
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
        // CREDENTIAL-WRITE-EXEMPT[2026-10-26]: route moves by conversation key, a file only splice writes or reads.
        val failed = Cancellables.runCatchingBestEffort { SecureFile.writeAtomic0600(path, JsonWire.string(json)) }
            .exceptionOrNull() ?: return
        val why = SafeFailureText.render(failed)
        log("[muse] could not save the gateway route moves at $path ($why); they hold until a restart")
    }

    /** At least half of the request's input was read from Meta's cache, so its cache was already on the route. */
    private fun warm(usage: Usage): Boolean = usage.inputTokens > 0 && usage.cachedTokens * 2 >= usage.inputTokens

    /** One turn's requests. The one sent last is unfinished from when Meta has all of it until Meta answers it or ends
     *  its stream, and a request Meta never finished may still be generating where it was sent. So whenever the turn
     *  sends again, or ends, with its last request unfinished, the conversation leaves that route first. A request Meta
     *  finished moves it too, when Meta held its headers past the limit while its cache was warm there. */
    private inner class TurnRoute(private val conversation: String) : VendorRoute {
        /** The route the request sent last rides. */
        @Volatile private var sent: String? = null

        /** That route, while Meta has all of the request and has not finished it. */
        private val unfinished = AtomicReference<String?>(null)

        /** When the request sent last went out whole, and how long after that its headers came, on [headerWait]'s
         *  clock. */
        @Volatile private var writtenAt: Long? = null

        @Volatile private var heldMs: Long? = null

        /** The cache key the request sent last carries in place of the one it was built with: the key of the route
         *  the conversation moved to, or null while it rides its first. */
        @Volatile private var sentKey: String? = null

        /** The turn is over. The transport hears a request went out whole on its own thread, so a turn the client left
         *  while the request's last bytes went out ends before it hears that, and leaves that request behind. */
        @Volatile private var ended = false

        /** The header and the key come from one look at the moves, so a request rides one route even while another
         *  turn of its conversation moves it. */
        override fun sending(): Map<String, String> {
            leftBehind("the turn sends again")
            val moved = movedTo(conversation)
            val route = moved ?: firstRoute(conversation)
            sent = route
            sentKey = moved?.let(::movedKey)
            writtenAt = null
            heldMs = null
            return mapOf(ROUTE_HEADER to route)
        }

        /** The body as built while the conversation rides its first route; otherwise a copy carrying the key of the
         *  route [sending] just chose. */
        override fun body(round: RoundBody): RoundBody? = sentKey?.let { key -> rekeyed(round, key) }

        override fun written() {
            writtenAt = headerWait.clock()
            unfinished.set(sent)
            if (ended) leftBehind("the turn ended")
        }

        override fun headersArrived() {
            heldMs = writtenAt?.let { at -> headerWait.clock() - at }
        }

        /** An answer finishes its request, except the gateway giving up, which leaves the generation behind it. */
        override fun answered(status: Int, body: String) {
            if (!gaveUp(status, body)) unfinished.set(null)
        }

        override fun finished(usage: Usage?) {
            unfinished.set(null)
            val route = sent ?: return
            val held = heldMs?.takeIf { it > headerWait.limitMs } ?: return
            val bill = usage?.takeIf(::warm) ?: return
            val why = "Meta held the headers of a warm request there ${TimeUnit.MILLISECONDS.toSeconds(held)} s " +
                "after it had all of it (${bill.cachedTokens} of ${bill.inputTokens} input tokens cached)"
            leave(conversation, route, why)
        }

        override fun turnEnded() {
            ended = true
            leftBehind("the turn ended")
        }

        private fun leftBehind(what: String) {
            val route = unfinished.getAndSet(null) ?: return
            leave(conversation, route, "$what before Meta finished its last request there")
        }
    }
}
