// NEW: Oct 11, 2026 — tags every request row with its project. A row names its session by the first eight
// characters of the id; this joins those tags to the project the SAME resolver gives the session rows, from the
// sessions the daemon knows (registry and transcript history) and from every session a Teams slot ever held,
// which fall back to their team's project. One snapshot per request: the map is built when the route asks and
// every row of that answer reads it.
package splice.app.control.projects

import kotlinx.serialization.json.JsonObject
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.projects.ProjectWire
import splice.sessions.registry.ProjectRef
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionTitleOf
import splice.usage.perf.ProjectTags
import splice.usage.perf.RequestProjects

// The perf row's session tag length (TurnDrive.SESSION_TAG_CHARS, in a module this one does not import).
private const val REQUEST_TAG_CHARS = 8

internal class RequestProjectTags(
    private val sessions: SessionSource,
    private val teams: TeamSource,
    private val routes: SessionsRoutes,
) : RequestProjects {
    override fun invoke(): ProjectTags {
        val byTag = LinkedHashMap<String, ProjectRef>()
        val unnamed = HashMap<String, SessionRecord>()
        val places = Places(routes)
        sessions.read().forEach { record ->
            val id = record.sessionId ?: return@forEach
            places.of(record)?.let { byTag.putIfAbsent(id.take(REQUEST_TAG_CHARS), it) }
            if (record.name.isNullOrBlank()) unnamed.putIfAbsent(id.take(REQUEST_TAG_CHARS), record)
        }
        teams()?.teams().orEmpty().forEach { team ->
            val own = team.repo.takeIf { it.isNotBlank() }?.let { routes.projects.at(it, null) } ?: return@forEach
            team.slots.flatMap { it.sessionsHistory }.forEach { byTag.putIfAbsent(it.take(REQUEST_TAG_CHARS), own) }
        }
        return Snapshot(byTag, unnamed, routes.titleOf)
    }

    // why: one resolution per folder and head, not per session: each one reads the merged config twice, and a registry
    // holds thousands of sessions in a few dozen folders (jfr on desk c, Oct 12: a fifth of a Usage read)
    private class Places(private val routes: SessionsRoutes) {
        private val known = HashMap<Pair<String, String?>, ProjectRef?>()

        fun of(record: SessionRecord): ProjectRef? = record.process.cwd?.let { cwd ->
            val place = cwd to record.head
            if (place in known) known[place] else routes.projects.at(cwd, record.head).also { known[place] = it }
        }
    }

    private class Snapshot(
        private val byTag: Map<String, ProjectRef>,
        private val unnamed: Map<String, SessionRecord>,
        private val titleOf: SessionTitleOf,
    ) : ProjectTags {
        private val wire = HashMap<String, JsonObject>()
        private val titles = HashMap<String, String?>()
        private val tagsByProject: Map<String, Set<String>> =
            byTag.entries.groupBy({ it.value.id }, { it.key }).mapValues { it.value.toSet() }

        override fun of(tag: String): JsonObject? = byTag[tag]?.let { wire.getOrPut(it.id) { ProjectWire.json(it) } }

        override fun tagsOf(id: String): Set<String> = tagsByProject[id].orEmpty()

        // Read when a row asks, never for the whole registry: a title is a file read the first time.
        override fun titleOf(tag: String): String? =
            titles.getOrPut(tag) { unnamed[tag]?.let { titleOf(it) } }
    }
}
