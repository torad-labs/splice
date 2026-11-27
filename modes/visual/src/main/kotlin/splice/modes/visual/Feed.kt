// NEW: what the visual mode's feeds are made of (modes/visual): one feed as held, and what writing one came to.
package splice.modes.visual

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.time.Instant

/** One feed as held: its name, when it was written, and its value as the compact JSON text it was checked as, in
 *  UTF-8. The text and never a parsed tree is what is held, so a feed holds the bytes its cap counts and no more. */
internal class Feed(val name: String, private val json: ByteArray, val updated: Instant) {
    /** The value's size as the cap counts it: its compact JSON in UTF-8. */
    val bytes: Int get() = json.size

    /** The value to put in an answer: its text as it is held, written out as it stands and never parsed again. It is
     *  strict JSON and never `null`, the two things an unquoted literal must be. */
    @OptIn(ExperimentalSerializationApi::class)
    fun value(): JsonElement = JsonUnquotedLiteral(json.decodeToString())
}

/** What writing a feed came to: kept, with the feed dropped to make room when one was, or refused in words. */
internal sealed class FeedWrite {
    data class Kept(val feed: Feed, val dropped: String?) : FeedWrite()

    data class Refused(val status: Int, val why: String) : FeedWrite()
}
