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
package splice.core.compaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.MonoClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private const val PROJECT_MISS_TTL_MS = 5_000L
private const val MAX_PROJECT_MISSES = 1_024

/** Maps Claude Code's session id to its working directory. The live registry wins; headless runs
 *  fall back to the transcript whose directory encodes the cwd and whose rows retain it exactly. */
public class SessionProject(
    private val sessionsDir: Path = Paths.get(System.getProperty("user.home"), ".claude", "sessions"),
    private val projectsDir: Path = Paths.get(System.getProperty("user.home"), ".claude", "projects"),
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val headProjectsDirs: List<Path> = emptyList(),
) {
    // listOf(projectsDir), never `+ projectsDir`: a Path is an Iterable of its own name elements.
    private val projectsDirs: List<Path> = headProjectsDirs + listOf(projectsDir)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Path>()
    private val misses = LinkedHashMap<String, Long>()
    private val validSessionId = Regex("[A-Za-z0-9_-]{1,128}")

    public fun projectFor(sessionId: String?): Path? {
        if (sessionId == null || !validSessionId.matches(sessionId)) return null
        val existing = cache[sessionId]
        if (existing != null || recentMiss(sessionId)) return existing
        val project = registryProject(sessionId) ?: transcriptProject(sessionId)
        if (project == null) recordMiss(sessionId)
        return project?.let {
            cache.putIfAbsent(sessionId, it)
            cache[sessionId]
        }
    }

    /** An unresolved lookup (including unreadable files) is retried shortly, never remembered as
     *  permanent absence. Hits do not renew the TTL, so a new registry entry becomes visible. */
    private fun recentMiss(sessionId: String): Boolean = synchronized(misses) {
        val recordedAt = misses[sessionId]
        val fresh = recordedAt != null && clock() - recordedAt < PROJECT_MISS_TTL_MS
        if (!fresh) misses.remove(sessionId)
        fresh
    }

    private fun recordMiss(sessionId: String) {
        synchronized(misses) {
            misses[sessionId] = clock()
            if (misses.size > MAX_PROJECT_MISSES) misses.remove(misses.keys.first())
        }
    }

    private fun registryProject(sessionId: String): Path? = directoryEntries(sessionsDir)
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull(::readJson)
        .firstOrNull { JsonScalars.str(it, "sessionId") == sessionId }
        ?.let { JsonScalars.str(it, "cwd") }
        ?.let(::absolutePath)

    private fun transcriptProject(sessionId: String): Path? {
        val fileName = "$sessionId.jsonl"
        val walked = HashSet<Path>()
        return projectsDirs.asSequence()
            .filter { walked.add(realPath(it)) }
            .flatMap { directoryEntries(it).asSequence() }
            .filter { Files.isDirectory(it) }
            .map { it.resolve(fileName) }
            .filter { Files.isRegularFile(it) }
            .mapNotNull { cwdFromTranscript(it, sessionId) }
            .firstOrNull()
    }

    private fun cwdFromTranscript(file: Path, sessionId: String): Path? = Cancellables
        .runCatchingCancellable {
            Files.newBufferedReader(file).useLines { lines ->
                lines.mapNotNull(::parseJson)
                    .firstNotNullOfOrNull { row ->
                        val rowSession = JsonScalars.str(row, "sessionId")
                        if (rowSession == sessionId) JsonScalars.str(row, "cwd")?.let(::absolutePath) else null
                    }
            }
        }
        .getOrNull()

    private fun readJson(path: Path): JsonObject? = Cancellables
        .runCatchingCancellable { parseJson(Files.readString(path)) }
        .getOrNull()

    private fun parseJson(text: String): JsonObject? = Cancellables
        .runCatchingCancellable { json.parseToJsonElement(text).jsonObject }
        .getOrNull()

    private fun absolutePath(raw: String): Path? = Cancellables
        .runCatchingCancellable { Paths.get(raw).normalize().takeIf { it.isAbsolute } }
        .getOrNull()

    private fun realPath(dir: Path): Path = Cancellables
        .runCatchingCancellable { dir.toRealPath() }
        .getOrDefault(dir.toAbsolutePath().normalize())

    private fun directoryEntries(path: Path): List<Path> = Cancellables
        .runCatchingCancellable { Files.newDirectoryStream(path).use { it.toList() } }
        .getOrDefault(emptyList())
}
