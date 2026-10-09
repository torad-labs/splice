package splice.app.provider

import splice.core.model.HeadDiscoveredModels

/** What the head's endpoint published, as the provider builders read it. */
internal data class PublishedRoster(
    /** What the head's endpoint publishes, read when asked and never copied (V4-441): the roster is
     *  refreshed while the daemon runs, so a reader that wants the current answer holds this, not a list. */
    val discovered: HeadDiscoveredModels = HeadDiscoveredModels { emptyList() },
    /** Metadata already read before publishing the catalog; provider construction never repeats it. */
    val localRows: LocalRowsCheck? = null,
)
