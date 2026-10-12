// NEW: Oct 11, 2026 — the project of a team and of each of its slots, spelled as the session rows spell it
// (ProjectWire). A team's project is its declared repo; a slot's is its bound session's, else the team's.
package splice.sessions.projects

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.sessions.http.ProjectAt
import splice.sessions.registry.ProjectRef
import splice.sessions.teams.Team

/** The project the session [sessionId] works in, or null when the registry does not know it. */
public fun interface SessionProjectOf {
    public operator fun invoke(sessionId: String): ProjectRef?
}

internal class TeamProjects(private val projectAt: ProjectAt, private val sessionProject: SessionProjectOf) {
    /** [encoded] is [team] as the store serializes it, with `project` written on it and on every slot. */
    fun on(team: Team, encoded: JsonObject): JsonObject {
        val own = team.repo.takeIf { it.isNotBlank() }?.let { projectAt(it) }
        val slots = team.slots.map { slot ->
            slot.session?.let { sessionProject(it) } ?: own
        }
        val encodedSlots = (encoded["slots"] as? JsonArray).orEmpty()
        return JsonObject(
            encoded + mapOf<String, JsonElement>(
                "project" to wire(own),
                "slots" to JsonArray(
                    encodedSlots.mapIndexed { index, slot ->
                        JsonObject((slot as JsonObject) + ("project" to wire(slots.getOrNull(index))))
                    },
                ),
            ),
        )
    }

    private fun wire(project: ProjectRef?): JsonElement = project?.let(ProjectWire::json) ?: JsonNull
}
