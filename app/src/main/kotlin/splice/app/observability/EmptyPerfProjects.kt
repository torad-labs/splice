package splice.app.observability

import kotlinx.serialization.json.JsonObject
import splice.usage.perf.ProjectTags

internal object EmptyPerfProjects : ProjectTags {
    override fun of(tag: String): JsonObject? = null
    override fun tagsOf(id: String): Set<String> = emptySet()
    override fun titleOf(tag: String): String? = null
}
