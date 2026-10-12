// NEW: Oct 11, 2026 — the one answer to "which project is this?", asked by the session rows, the Teams
// slots, the request rows and /api/projects alike, so all of them agree (Marlin's ruling, Oct 11).
//
// With projectsFolders set, a project is the folder DIRECTLY under one of those folders that holds the
// working directory, named by that folder: mythos/repo, mythos/Splice-CPO, a worktree under mythos and a
// seat's home under mythos are all "mythos". A linked git worktree that lives OUTSIDE every folder
// (EnterWorktree's ~/.claude/worktrees) follows its repo: the repo's location is asked instead. A working
// directory outside every folder has no project.
// With none set, the project is the git repo (RepoResolver: trusted roots only, worktrees folded into
// their repo), named by its remote's repo name, else its folder. A cwd no repo holds has no project.
//
// NAME AND LABEL. [ProjectRef.name] is the plain name; [ProjectRef.label] is what a screen prints: the name,
// unless another project the answer could hold shares it. Then a project named by its git remote adds the owner
// ("torad-labs/splice") and one named by its folder adds the parent folder ("work/repo").
// In folder mode the rivals are the children of
// every configured folder; in repo mode they are the repos this resolver has been asked about.
package splice.sessions.registry

import splice.core.config.ProjectFolderCheck
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

/** Where a project came from: a folder under the Projects folder setting, or a git repo. */
public enum class ProjectSource(public val wire: String) {
    FOLDER("folder"),
    REPO("repo"),
}

/** A project as every screen names it: [id] is its folder (folder mode) or its git root (repo mode). */
public data class ProjectRef(
    val id: String,
    val name: String,
    val label: String,
    val source: ProjectSource,
    val folder: String? = null,
)

// why: the children of a projects folder change when a project is cloned; 2 s matches RepoResolver's cache, so one
// poll costs one listing and a new project shows within a refresh.
private const val CHILDREN_TTL_MS = 2_000L

// why: a repo-mode resolver learns rivals from the cwds it is asked about; an operator holds far fewer than 256.
private const val SEEN_REPOS_MAX = 256

// why: a listing and every request row ask about every session; a cwd's project moves only when a folder is renamed
// or a checkout is re-linked, so 15 s keeps one poll cheap and a change visible within a few refreshes.
private const val HELD_TTL_MS = 15_000L

// why: the real machine holds about 5,600 distinct working directories across its history; this is the headroom.
private const val HELD_MAX = 16_384

internal class ProjectResolver(
    folders: List<String>,
    private val repos: RepoResolver,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    /** Each folder as its realpath, so a symlinked cwd lands where the operator meant. */
    private val spelledAs = HashMap<Path, String>()
    private val roots: List<Path> = folders.mapNotNull { raw ->
        val real = PathProbe.spelled(ProjectFolderCheck.expanded(raw))?.let { PathProbe.resolved(it) ?: it.normalize() }
        real?.also { spelledAs.putIfAbsent(it, raw) }
    }
    private val seen = LinkedHashMap<String, Pair<String, String?>>()
    private val held = LinkedHashMap<String, Pair<Long, ProjectRef?>>()
    private var children: Pair<Long, Map<String, Int>> = 0L to emptyMap()

    /** The configured folders that exist now. A saved folder that has vanished is kept by settings and reported as not
     *  found, and the sessions under it fall back to the git-repo rule: a display preference never breaks a read. */
    private fun live(): List<Path> = roots.filter { Files.isDirectory(it) }

    /** The project of [cwd], held for a short while: a listing asks about every session, and most share a cwd. */
    fun of(cwd: String): ProjectRef? {
        val now = clock()
        synchronized(held) { held[cwd]?.takeIf { now < it.first }?.let { return relabelled(it.second) } }
        val project = if (live().isEmpty()) inRepo(cwd) else inFolder(cwd)
        synchronized(held) {
            held[cwd] = now + HELD_TTL_MS to project
            while (held.size > HELD_MAX) held.remove(held.keys.first())
        }
        return relabelled(project)
    }

    /** A repo project's label depends on the other repos seen so far, so it is worked out at every read, never held. */
    private fun relabelled(project: ProjectRef?): ProjectRef? {
        if (project == null || project.source != ProjectSource.REPO) return project
        val (others, owner) = synchronized(seen) {
            seen.count { (id, entry) -> entry.first == project.name && id != project.id } to seen[project.id]?.second
        }
        return project.copy(label = labelled(project.name, project.id, others, owner))
    }

    private fun inFolder(cwd: String): ProjectRef? {
        val project = holding(cwd)
            ?: repos.resolve(cwd).takeIf { it.reason == null }?.let { holding(it.root) }
            ?: return null
        val name = project.fileName.toString()
        val id = project.toString()
        return ProjectRef(id, name, labelled(name, id, rivals(name)), ProjectSource.FOLDER, spelledAs[project.parent])
    }

    /** The folder directly under a configured one that holds [path], or null when none does. */
    private fun holding(path: String): Path? {
        val spelled = PathProbe.spelled(path) ?: return null
        val here = PathProbe.resolved(spelled) ?: spelled.normalize()
        val root = live().filter { here != it && here.startsWith(it) }.maxByOrNull { it.nameCount } ?: return null
        return root.resolve(here.getName(root.nameCount).toString())
    }

    private fun inRepo(cwd: String): ProjectRef? {
        val repo = repos.resolve(cwd).takeIf { it.reason == null } ?: return null
        val remote = RepoOrigin.of(repo.root)?.let(::remoteOf)
        val name = remote?.second ?: PathProbe.spelled(repo.root)?.fileName?.toString() ?: return null
        synchronized(seen) {
            seen[repo.root] = name to remote?.first
            while (seen.size > SEEN_REPOS_MAX) seen.remove(seen.keys.first())
        }
        return ProjectRef(repo.root, name, name, ProjectSource.REPO)
    }

    /** How many other configured-folder children carry [name]. */
    @Synchronized
    private fun rivals(name: String): Int {
        val now = clock()
        val (at, counts) = children
        val table = if (now - at < CHILDREN_TTL_MS && at != 0L) {
            counts
        } else {
            live().flatMap(::subfolders).groupingBy { it }.eachCount().also { children = now to it }
        }
        return (table[name] ?: 1) - 1
    }

    private fun subfolders(root: Path): List<String> =
        PathProbe.entries(root).filter { Files.isDirectory(it) }.map { it.fileName.toString() }

    /** The name, or its last two segments when [rivals] other projects carry it. */
    private fun labelled(name: String, id: String, rivals: Int, owner: String? = null): String = when {
        rivals == 0 -> name
        owner != null -> "$owner/$name"
        else -> "${PathProbe.spelled(id)?.parent?.fileName ?: ""}/$name".trimStart('/')
    }

    /** `git@host:org/name.git` and `https://host/org/name.git` are both owner "org", name "name". */
    private fun remoteOf(remote: String): Pair<String?, String>? {
        val parts = remote.trimEnd('/').removeSuffix(".git").split('/', ':').filter { it.isNotBlank() }
        val name = parts.lastOrNull() ?: return null
        return parts.getOrNull(parts.size - 2) to name
    }
}
