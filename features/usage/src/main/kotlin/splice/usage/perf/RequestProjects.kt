// NEW: Oct 11, 2026 — which project each request row belongs to. A row carries the 8-character tag of its session;
// the daemon knows each session's working directory, so it answers tag -> project here, once per request, and the
// rows print the same `project` object the session rows do. This module reads no registry of its own: the port is
// filled by the application from the one project resolver.
package splice.usage.perf

import kotlinx.serialization.json.JsonObject

/** One read's answer: the project object of a session tag, and the tags a project id covers. */
public interface ProjectTags {
    /** The `{id, name, label, source}` object of the session [tag] starts, or null when unknown or outside every project. */
    public fun of(tag: String): JsonObject?

    /** Every session tag that belongs to the project [id]; empty for an id nothing holds. */
    public fun tagsOf(id: String): Set<String>
}

/** No project knowledge wired: every row has none. */
internal object NoProjectTags : ProjectTags {
    override fun of(tag: String): JsonObject? = null

    override fun tagsOf(id: String): Set<String> = emptySet()
}

/** Taken once per request, so every row of one answer reads the same snapshot. */
public fun interface RequestProjects {
    public operator fun invoke(): ProjectTags
}
