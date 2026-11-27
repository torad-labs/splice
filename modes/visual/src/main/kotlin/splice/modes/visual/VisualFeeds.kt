// NEW: the feeds of the visual mode (modes/visual): a feed is a small named JSON value the mode holds in the daemon's
// memory, so a card drawn in a frame that reaches no network can stay up to date. Something writes it (the `feed` tool
// with `data`, or `PUT /api/visual/feeds/<name>`, which `splice feed` calls from a script), and the card reads it back
// through the app that frames it (`feed` without `data`, which the view's readFeed and watchFeed ask the app to call),
// or an app that is not an MCP host reads it at `GET /api/visual/feeds/<name>`, which answers what the tool does. Each
// value carries when it was written.
//
// Kept in memory on purpose. The mode's store writes each value to disk, fsynced, before it answers; a script that
// writes a feed every second would make 86,400 such writes a day for one feed, where the daemon's other per-row
// fsync is budgeted at a write every 30 seconds per session (docs/specs/web-console.md, "Storage"). A feed would also
// become content kept at rest (docs/wiki/disk-and-privacy.md). And a restart costs a card nothing it cannot do
// without: it drew its first state from data inlined in the card, and the script that writes the feed writes it again
// within its own interval.
//
// A value is held as the compact JSON text it was checked as, in UTF-8, and never as the parsed tree it arrived in: a
// tree of 64 KiB of small numbers is about twelve times its text in objects, so 64 full feeds held as trees came to
// about 48 MiB. As text they hold 64 x 64 KiB = 4 MiB, plus a name and a time each.
package splice.modes.visual

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.HttpStatus
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import java.time.Clock
import java.time.temporal.ChronoUnit

// why: a person's cards on one machine are a few; 64 feeds hold every card of many conversations, and with the value
// cap below bound what the mode holds to 4 MiB of values. A 65th feed drops the one written longest ago.
private const val MAX_FEEDS = 64

// why: a card draws a few numbers or a short list, and a watching card is handed the whole value on every read, so a
// value is small; 64 KiB holds a few thousand points and refuses a log. Measured as the value's compact JSON in UTF-8,
// and said to the model as "64 KiB" below.
private const val MAX_VALUE_BYTES = 64 * 1024

// why: a name is typed into a command and a card's script, so it is short; 40 characters fit `training_loss_run_12`
// and longer, and the characters are those safe in a path, a shell word and a URL without escaping.
private const val NAME_MOST_CHARS = 40
private val FEED_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,${NAME_MOST_CHARS - 1}}")

private const val NOT_STRICT =
    "the value is not strict JSON: write a number as 1 or 1.5 (no leading zero, no +, no NaN or Infinity), a string " +
        "in double quotes, and true, false, [...] or {...}"

private const val NO_NULL =
    "a feed holds a value, and null is none: leave data out to read the feed, or write another value"

internal class VisualFeeds(private val clock: Clock = Clock.systemUTC()) {
    /** The feeds in the order they were written, the oldest first; every use holds its own lock. */
    private val held = LinkedHashMap<String, Feed>()
    private val strict = StrictJson()

    /** The `feed` tool: [input] with `data` writes the feed `name` and without it reads it; `data: null` is a write
     *  of null, refused as the route refuses it. A feed that is not there is answered as a plain `found: false`,
     *  never as an error, so a card that asks early is not broken by it. */
    fun call(input: JsonObject): ModeAnswer {
        val name = (input["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val data = input["data"]
        val wrong = refusalOfName(name)
        return when {
            wrong != null -> ModeAnswer.Error(wrong)
            data == null -> ModeAnswer.Text(shown(name, read(name)).toString())
            else -> when (val written = write(name, data)) {
                is FeedWrite.Kept -> ModeAnswer.Text(said(written))
                is FeedWrite.Refused -> ModeAnswer.Error(written.why)
            }
        }
    }

    /** `GET /api/visual/feeds/<name>`: what the tool reads, `{ name, found, updated, data }`, with `found: false` and
     *  no more for a feed that is not held, as the tool answers it; 400 for a name that is not one. */
    fun get(request: ModeRequest): ModeResponse {
        val name = request.path.substringAfterLast('/')
        val wrong = refusalOfName(name)
        return if (wrong != null) {
            failure(HttpStatus.BAD_REQUEST, wrong)
        } else {
            ModeResponse(HttpStatus.OK, shown(name, read(name)))
        }
    }

    /** `PUT /api/visual/feeds/<name>`: the body is the feed's value, as strict JSON. Answers
     *  `{ said, name, updated, bytes }`, or the refusal's status and `{ error }`. */
    fun put(request: ModeRequest): ModeResponse {
        val name = request.path.substringAfterLast('/')
        val value = request.body
        return if (value == null) {
            failure(HttpStatus.BAD_REQUEST, "send the feed's value as the JSON body of the request")
        } else {
            when (val written = write(name, value)) {
                is FeedWrite.Refused -> failure(written.status, written.why)
                is FeedWrite.Kept -> ModeResponse(
                    HttpStatus.OK,
                    buildJsonObject {
                        put("said", said(written))
                        put("name", name)
                        put("updated", written.feed.updated.toString())
                        put("bytes", written.feed.bytes)
                    },
                )
            }
        }
    }

    private fun read(name: String): Feed? = synchronized(held) { held[name] }

    private fun write(name: String, data: JsonElement): FeedWrite {
        val wrong = refusalOfName(name) ?: NO_NULL.takeIf { data is JsonNull }
        val json = if (wrong == null) strict.bytes(data) else null
        return when {
            wrong != null -> FeedWrite.Refused(HttpStatus.BAD_REQUEST, wrong)
            json == null -> FeedWrite.Refused(HttpStatus.BAD_REQUEST, NOT_STRICT)
            json.size > MAX_VALUE_BYTES -> FeedWrite.Refused(
                HttpStatus.CONTENT_TOO_LARGE,
                "the value is ${json.size} bytes as JSON, and a feed holds at most 64 KiB ($MAX_VALUE_BYTES " +
                    "bytes): write only what the card draws, such as the last few hundred points",
            )
            else -> keep(Feed(name, json, clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        }
    }

    /** [feed] as the newest, and the oldest dropped when that makes more than [MAX_FEEDS]. */
    private fun keep(feed: Feed): FeedWrite.Kept = synchronized(held) {
        held.remove(feed.name)
        held[feed.name] = feed
        val oldest = if (held.size > MAX_FEEDS) held.keys.first() else null
        if (oldest != null) held.remove(oldest)
        FeedWrite.Kept(feed, oldest)
    }

    private fun refusalOfName(name: String): String? = if (FEED_NAME.matches(name)) {
        null
    } else {
        "a feed's name is 1 to $NAME_MOST_CHARS letters, digits, - or _, starting with a letter or a digit, such as " +
            "training_loss"
    }

    /** The feed as a reader is told it: its name, whether it is held, and when it was written and its value if so.
     *  The value goes in as the text it is held as, never parsed again into a tree. */
    private fun shown(name: String, feed: Feed?): JsonObject = buildJsonObject {
        put("name", name)
        put("found", feed != null)
        if (feed != null) {
            put("updated", feed.updated.toString())
            put("data", feed.value())
        }
    }

    private fun said(kept: FeedWrite.Kept): String {
        val dropped = kept.dropped?.let {
            " The feed $it, written longest ago, was dropped to make room: the mode holds at most $MAX_FEEDS."
        }
        val feed = kept.feed
        return "Feed ${feed.name} written at ${feed.updated}, ${feed.bytes} bytes.${dropped.orEmpty()}"
    }

    private fun failure(status: Int, why: String): ModeResponse =
        ModeResponse(status, buildJsonObject { put("error", why) })
}
