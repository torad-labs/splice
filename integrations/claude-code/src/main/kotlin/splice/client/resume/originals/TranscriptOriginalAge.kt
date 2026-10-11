// NEW: Oct 10, 2026 — which transcript copies a history cut reaches, by when their session was last used.
//
// A copy is kept so a session can be resumed after its head changed the file, so its age is the session's, not the
// copy's: the newest write among the live files it was copied from (the sources the marker records). A copy whose
// sources are all gone is aged by its own newest write, which is when it was last touched. Resumability is not the
// test (Marlin, Oct 10): nearly everything is resumable while its copy exists. The caller holds the originals lock.
package splice.client.resume.originals

import kotlinx.serialization.json.Json
import splice.client.resume.TRANSCRIPT_SUFFIX
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Comparator

private const val SOURCES_SUFFIX = ".sources.json"

/** What a cut would take of the transcript copies: the bytes and the number of files. */
public data class OriginalsHeld(val bytes: Long, val files: Int)

internal class TranscriptOriginalAge(private val root: Path) {
    fun held(momentMs: Long): OriginalsHeld {
        val files = reached(momentMs).flatMap { it.value }
        return OriginalsHeld(files.sumOf { Files.size(it) }, files.size)
    }

    fun delete(momentMs: Long): OriginalsHeld {
        val reached = reached(momentMs)
        var bytes = 0L
        var removed = 0
        for ((session, files) in reached) {
            for (file in files.sortedByDescending { it.nameCount }) {
                bytes += Files.size(file)
                Files.delete(file)
                removed++
            }
            removeEmpty(session)
        }
        return OriginalsHeld(bytes, removed)
    }

    /** Every session older than [momentMs], with the regular files that are its copy. */
    private fun reached(momentMs: Long): Map<Path, List<Path>> {
        val reached = LinkedHashMap<Path, List<Path>>()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return reached
        for (project in children(root).filter { Files.isDirectory(it, NOFOLLOW_LINKS) }) {
            for (id in ids(project)) {
                val files = filesOf(project, id)
                if (files.isNotEmpty() && activity(project, id, files) < momentMs) {
                    reached[project.resolve(id)] = files
                }
            }
        }
        return reached
    }

    private fun ids(project: Path): Set<String> = children(project).map { it.fileName.toString() }
        .filter { !it.startsWith(".") }
        .map { it.removeSuffix(SOURCES_SUFFIX).removeSuffix(TRANSCRIPT_SUFFIX) }
        .filter { originalSessionId.matches(it) }
        .toSortedSet()

    private fun filesOf(project: Path, id: String): List<Path> {
        val own = listOf(project.resolve(id + TRANSCRIPT_SUFFIX), project.resolve(id + SOURCES_SUFFIX))
            .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        val nested = try {
            Files.walk(project.resolve(id)).use { walk ->
                walk.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList()
            }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        return own + nested
    }

    private fun activity(project: Path, id: String, files: List<Path>): Long {
        val sources = try {
            Json.decodeFromString<List<String>>(Files.readString(project.resolve(id + SOURCES_SUFFIX)))
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        val live = sources.mapNotNull { stamp(Path.of(it)) }
        return live.maxOrNull() ?: files.maxOf { stamp(it) ?: Long.MIN_VALUE }
    }

    private fun stamp(file: Path): Long? = try {
        Files.getLastModifiedTime(file).toMillis()
    } catch (_: NoSuchFileException) {
        null
    }

    private fun removeEmpty(session: Path) {
        try {
            Files.walk(session).use { walk -> walk.sorted(Comparator.reverseOrder()).toList() }
                .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                .forEach { Files.deleteIfExists(it) }
        } catch (_: NoSuchFileException) {
            // This session kept no nested originals.
        }
        val project = session.parent
        if (children(project).isEmpty()) Files.deleteIfExists(project)
    }

    private fun children(dir: Path): List<Path> = try {
        Files.list(dir).use { it.toList() }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (error: IOException) {
        throw IOException("cannot read $dir: ${SafeFailureText.render(error)}", error)
    }
}
