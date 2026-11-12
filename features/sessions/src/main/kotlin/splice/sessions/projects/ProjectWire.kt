// NEW: Oct 11, 2026 — a project as every wire object spells it: session rows, Teams, request rows and
// /api/projects all print this one object, so the console reads it one way.
package splice.sessions.projects

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.registry.ProjectRef

/** `{id, name, label, source, folder?}`: [ProjectRef.label] is what a screen prints, [ProjectRef.name] the plain name,
 *  and `folder` the saved Projects folder a folder project came from (absent for a repo project). */
public object ProjectWire {
    public fun json(project: ProjectRef): JsonObject = buildJsonObject {
        put("id", project.id)
        put("name", project.name)
        put("label", project.label)
        put("source", project.source.wire)
        project.folder?.let { put("folder", it) }
    }
}
