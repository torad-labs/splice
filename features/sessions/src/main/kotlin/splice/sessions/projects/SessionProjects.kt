// NEW: Oct 12, 2026 — which project a session, a working directory or a request belongs to, for every screen.
package splice.sessions.projects

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.core.config.UserHome
import splice.sessions.http.SessionSettings
import splice.sessions.registry.ProjectRef
import splice.sessions.registry.ProjectResolver
import splice.sessions.registry.RepoResolver
import splice.sessions.registry.SessionRecord
import java.util.concurrent.ConcurrentHashMap

/** One answer to "which project is this?" per setting set: the resolvers are kept per (git roots, Projects folders)
 *  pair, because both are per-head overridable and a resolver's caches must survive between polls. */
public class SessionProjects(private val settings: SessionSettings) {
    private val repos = ConcurrentHashMap<List<String>, RepoResolver>()
    private val resolvers = ConcurrentHashMap<Pair<List<String>, List<String>>, ProjectResolver>()

    /** The project the session belongs to; null without a cwd or outside every project. */
    public fun of(record: SessionRecord): ProjectRef? = record.process.cwd?.let { at(it, record.head) }

    /** The project a working directory belongs to for [head]: a Teams slot, a team repo or a request row asks here. */
    public fun at(cwd: String, head: String?): ProjectRef? =
        resolvers.computeIfAbsent(settings.gitRoots(head) to settings.projectFolders(head)) { (roots, folders) ->
            ProjectResolver(folders, repos.computeIfAbsent(roots) { RepoResolver(it) })
        }.of(cwd)

    /** The Projects folders saved in settings (global), as written. */
    public fun folders(): List<String> = settings.projectFolders(null)

    /** Writes `project` on a row when the session has one, and `place` whenever it has a working directory:
     *  the folder as a person writes it from home (`~/notes`), or the absolute path outside home. */
    public fun put(row: JsonObjectBuilder, record: SessionRecord) {
        of(record)?.let { row.put("project", ProjectWire.json(it)) }
        record.process.cwd?.let { row.put("place", place(it)) }
    }

    /** [cwd] with the home folder written as `~`. */
    public fun place(cwd: String): String {
        val home = UserHome.dir().toString()
        return when {
            cwd == home -> "~"
            cwd.startsWith("$home/") -> "~" + cwd.removePrefix(home)
            else -> cwd
        }
    }
}
