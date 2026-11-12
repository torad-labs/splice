// NEW: retire a session's originals only after every recorded live transcript is positively absent.
package splice.client.resume.originals

import kotlinx.serialization.json.Json
import splice.client.resume.TRANSCRIPT_SUFFIX
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Comparator

private val STAGED_ORIGINAL = Regex("""\.original-[0-9a-f-]{36}\.tmp""")

internal class TranscriptOriginalSweep(private val root: Path, private val log: LogSink) {
    fun run() {
        val projects = Files.list(root).use { it.filter { path -> Files.isDirectory(path, NOFOLLOW_LINKS) }.toList() }
        for (project in projects) {
            val abandoned = Files.walk(project).use {
                it.filter { path -> path.fileName.toString().matches(STAGED_ORIGINAL) }.toList()
            }
            for (staged in abandoned) Files.deleteIfExists(staged)
            val markers = Files.list(project).use {
                it.filter { path -> path.fileName.toString().endsWith(".sources.json") }.toList()
            }
            for (marker in markers) retire(marker)
        }
    }

    private fun retire(marker: Path) {
        try {
            val id = validatedId(marker)
            val sources = Json.decodeFromString<List<String>>(Files.readString(marker))
            if (sources.isEmpty()) throw IOException("Transcript original has no source evidence")
            if (!sources.all { missing(Path.of(it)) }) return
            Files.deleteIfExists(marker.resolveSibling(id + TRANSCRIPT_SUFFIX))
            removeChildren(marker.resolveSibling(id))
            Files.delete(marker)
        } catch (error: IOException) {
            unresolved(error)
        } catch (error: IllegalArgumentException) {
            unresolved(error)
        }
    }

    private fun validatedId(marker: Path): String {
        val id = marker.fileName.toString().removeSuffix(".sources.json")
        if (!originalSessionId.matches(id)) throw IOException("Original source marker has an invalid session id")
        if (Files.readAttributes(marker, "basic:isRegularFile", NOFOLLOW_LINKS)["isRegularFile"] != true) {
            throw IOException("Original source marker is not a regular file")
        }
        return id
    }

    private fun removeChildren(children: Path) {
        try {
            val files = Files.walk(children).use { it.sorted(Comparator.reverseOrder()).toList() }
            for (file in files) Files.delete(file)
        } catch (_: NoSuchFileException) {
            // This session kept no nested originals.
        }
    }

    private fun missing(path: Path): Boolean = try {
        val attributes = Files.readAttributes(path, "basic:isRegularFile")
        if (!attributes.containsKey("isRegularFile")) throw IOException("Transcript source probe did not answer")
        false
    } catch (_: NoSuchFileException) {
        true
    }

    private fun unresolved(error: Exception) {
        log("[resume] original sweep kept an unresolved session (${SafeFailureText.render(error)})\n")
    }
}
