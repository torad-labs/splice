// NEW: Oct 12, 2026 — what a project's files route reads: the repo's own instruction files and each head's client memory.
// Split from ProjectsRoutes so the route keeps the project views and this keeps the disk reads. Nothing is ever written.
package splice.sessions.projects

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.PathProbe
import splice.sessions.query.SessionHead
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The repo files read as a project's own instructions, in the order they are listed. */
private val INSTRUCTION_FILES = listOf("CLAUDE.md", "AGENTS.md")
private const val MEMORY_SUFFIX = ".md"

internal class ProjectFiles(private val heads: Map<String, SessionHead>) {
    private val slugChars = Regex("[^A-Za-z0-9]")

    fun read(id: String): JsonObject {
        val root = Paths.get(id)
        val slug = slugChars.replace(id, "-")
        val memoryDirs = heads.mapNotNull { (name, head) ->
            head.transcriptRoot?.let { name to it.resolve("projects").resolve(slug).resolve("memory") }
        }
        val files = INSTRUCTION_FILES.mapNotNull { file(root.resolve(it), "instructions", null) } +
            memoryDirs.flatMap { (name, dir) -> memoryFiles(dir).mapNotNull { file(it, "memory", name) } }
        val body = buildJsonObject {
            put("id", id)
            put("files", buildJsonArray { files.forEach(::add) })
        }
        return body
    }

    private fun file(path: Path, kind: String, head: String?): JsonObject? {
        if (!Files.isRegularFile(path)) return null
        // A file that vanished or cannot be read between the check and the read is left out.
        val text = PathProbe.text(path) ?: return null
        return buildJsonObject {
            put("kind", kind)
            put("path", path.toString())
            put("head", head)
            put("text", text)
        }
    }

    private fun memoryFiles(dir: Path): List<Path> =
        // No memory directory is no memory files.
        PathProbe.entries(dir)
            .filter { it.fileName.toString().endsWith(MEMORY_SUFFIX) }
            .sorted()
}
