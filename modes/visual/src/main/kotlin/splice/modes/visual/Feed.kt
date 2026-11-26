// NEW: what the visual mode's feeds are made of (modes/visual): one feed as held, and what writing one came to.
package splice.modes.visual

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** One feed as held: its value, when it was written, and the value's size in bytes. */
internal data class Feed(val name: String, val data: JsonElement, val updated: Instant, val bytes: Int)

/** What writing a feed came to: kept, with the feed dropped to make room when one was, or refused in words. */
internal sealed class FeedWrite {
    data class Kept(val feed: Feed, val dropped: String?) : FeedWrite()

    data class Refused(val status: Int, val why: String) : FeedWrite()
}
