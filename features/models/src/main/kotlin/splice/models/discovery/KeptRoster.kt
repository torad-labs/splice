// NEW: Oct 12, 2026 — what RosterCache answers when asked for a head's kept list. Split from RosterCache, which
// reads and writes the file.
package splice.models.discovery

import splice.core.model.DiscoveredModel

/** A head's kept list, or why there is none to stand in for its endpoint. */
public sealed class KeptRoster {
    /** The list the endpoint published last, at the URL it is asked at now. */
    public data class Kept(
        val models: List<DiscoveredModel>,
        val metadata: List<DiscoveredModel> = models,
    ) : KeptRoster()

    /** No list was ever kept for this head. */
    public data object None : KeptRoster()

    /** The list kept was published at [url], a different endpoint, and says nothing about this one. */
    public data class OtherUrl(val url: String) : KeptRoster()

    /** The kept file is there and could not be read: [reason]. */
    public data class Unreadable(val reason: String) : KeptRoster()
}
