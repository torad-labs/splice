// NEW: v0.4.0 FEATURES.md §7 — a Claude Code session id mapped to its working directory: the live registry
// first, the transcript directory for headless runs.
//
// V4-130: THE PROJECTS TREES ARE A LIST, HEAD TREES FIRST. Since V4-115 a head may keep its own
// CLAUDE_CONFIG_DIR/projects (nine heads do, measured 2026-09-18; four heads' projects dir is a
// symlink to ~/.claude/projects), so the single vanilla default this class used to read missed every
// headless session of a head with its own tree. The daemon passes every head's projects tree as
// [headProjectsDirs] (the topology's config dirs, the same set LaunchSpecFactory derives HeadTrees
// from); they are searched before [projectsDir], the vanilla tree, which stays the fallback. Each
// tree is walked once by its real path, so a symlinked head's tree and the vanilla one are one walk. The first tree holding the transcript wins; the cwd a transcript records is the
// same in every copy of one session, so the order decides only how soon the lookup ends, never the
// answer. Reading only: nothing here writes into any tree (HEAD ISOLATION).
//
// THE LIVE REGISTRY IS A LIST TOO (spec §13, Oct 13). Claude Code registers a running session under its own
// CLAUDE_CONFIG_DIR/sessions (SessionRegistryLink's header: it lists that folder to find its peers), so a head that
// isolates sessions registers only in its own folder. Each head's sessions folder, beside its projects tree, is read
// before the vanilla [sessionsDir], each once by its real path: on a head whose sessions folder links to the vanilla
// one, as every head's does on the operator's machine, that is one read. The transcript stays the fallback.
package splice.core.compaction

import splice.core.config.UserHome
import splice.core.memory.HeapReservations
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

/** Maps Claude Code's session id to its working directory. The live registry wins; headless runs
 *  fall back to the transcript whose directory encodes the cwd and whose rows retain it exactly. */
public class SessionProject(
    private val sessionsDir: Path = UserHome.claudeDir().resolve("sessions"),
    private val projectsDir: Path = UserHome.claudeDir().resolve("projects"),
    clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val headProjectsDirs: List<Path> = emptyList(),
    heap: HeapReservations? = null,
) {
    // listOf(projectsDir), never `+ projectsDir`: a Path is an Iterable of its own name elements.
    private val projectsDirs: List<Path> = headProjectsDirs + listOf(projectsDir)

    // each head's live-session registry folder sits beside its projects tree, under the name Claude Code gives it
    private val sessionsDirs: List<Path> = headProjectsDirs.map { it.resolveSibling("sessions") } + listOf(sessionsDir)

    private val files = ProjectLookupFiles(clock, heap)
    private val validSessionId = Regex("[A-Za-z0-9_-]{1,128}")

    public fun projectFor(sessionId: String?): Path? {
        if (sessionId == null || !validSessionId.matches(sessionId)) return null
        // Consult registry evidence even on transcript hits: a new higher-priority registration wins immediately.
        return registryProject(sessionId) ?: transcriptProject(sessionId)
    }

    private fun registryProject(sessionId: String): Path? {
        val read = HashSet<Path>()
        return sessionsDirs.asSequence()
            .filter { read.add(realPath(it)) }
            .flatMap { files.entries(it).asSequence() }
            .filter { it.fileName.toString().endsWith(".json") }
            .mapNotNull(files::registration)
            .firstOrNull { it.sessionId == sessionId }
            ?.cwd
    }

    private fun transcriptProject(sessionId: String): Path? {
        val fileName = "$sessionId.jsonl"
        val walked = HashSet<Path>()
        return projectsDirs.asSequence()
            .filter { walked.add(realPath(it)) }
            .flatMap { files.entries(it).asSequence() }
            .filter { Files.isDirectory(it) }
            .map { it.resolve(fileName) }
            .mapNotNull { files.transcript(it, sessionId) }
            .firstOrNull()
    }

    // An unresolvable dir is deduplicated by its normalized absolute path instead.
    private fun realPath(dir: Path): Path = PathProbe.resolved(dir) ?: dir.toAbsolutePath().normalize()
}
