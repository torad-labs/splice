// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). The transcript a `claude` keeps for its
// session, kept by the translator in Claude Code's own place and shape,
// `<config>/projects/<folder>/<session id>.jsonl`, so Claude Desktop reopens a Gemini session with its history: one
// line for each user message, assistant message and tool result, each naming the one before it (`parentUuid`). A
// resumed session's lines follow the last one already there. Claude Code reads the same lines, which the switch back
// from Gemini (decision 5) relies on.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.ClaudeProjectFolder
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.JsonlSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

/** Who writes the transcript's lines: the session, its folder, and the `claude` it stands in for. */
internal data class TranscriptOwner(
    val sessionId: String,
    val cwd: String,
    val version: String,
    val entrypoint: String,
)

/** Session [owner]'s transcript under Claude's [config] folder, its lines stamped by [clock]. */
internal class ClaudeTranscript(config: Path, private val owner: TranscriptOwner, private val clock: WallClock) {
    val file: Path =
        config.resolve(TRANSCRIPTS_FOLDER).resolve(ClaudeProjectFolder.of(owner.cwd))
            .resolve("${owner.sessionId}.jsonl")
    private var last: String? = lastUuid()

    // Whether the transcript's folder stands — believed from when we last made it or wrote into it.
    private var foldersMade = false

    /** Desktop's user message [uuid] with [content]. */
    fun user(uuid: String, content: JsonElement) = userLine(uuid, content)

    /** The assistant [message] (a whole Anthropic message) said as [uuid]. */
    fun assistant(uuid: String, message: JsonObject) = write(uuid, "assistant") { put("message", message) }

    /** The result blocks [content] of a tool the assistant line before it called. */
    fun toolResult(uuid: String, content: JsonElement) = userLine(uuid, content)

    private fun userLine(uuid: String, content: JsonElement) = write(uuid, "user") {
        putJsonObject("message") {
            put("role", "user")
            put("content", content)
        }
    }

    private inline fun write(uuid: String, type: String, body: JsonObjectBuilder.() -> Unit) {
        val line = buildJsonObject {
            put("parentUuid", last)
            put("isSidechain", false)
            put("userType", "external")
            put("cwd", owner.cwd)
            put("sessionId", owner.sessionId)
            put("version", owner.version)
            put("entrypoint", owner.entrypoint)
            put("type", type)
            body()
            put("uuid", uuid)
            put("timestamp", Instant.ofEpochMilli(clock()).toString())
        }
        append((JsonWire.string(line) + "\n").toByteArray(Charsets.UTF_8))
        last = uuid
    }

    /** One encoded line into the transcript. The folder is made when its first line lands, and not asked after again
     *  until something proves otherwise — a folder taken away mid-session costs one failed open, never a lost turn:
     *  Desktop may tidy a projects tree between turns, so a line that finds its folder gone remakes it and lands anyway,
     *  as the per-line createDirectories this memo replaced always did.
     *
     * A transcript is never rotated: Desktop and Claude Code read it whole, as Claude Code writes it. */
    private fun append(bytes: ByteArray) {
        if (!foldersMade) makeFolders()
        try {
            JsonlSink.appendLine(file, bytes, Long.MAX_VALUE, JsonlSink.NO_ARCHIVE, JsonlSink.PAGE_CACHE_FORCE)
        } catch (ignored: NoSuchFileException) {
            foldersMade = false
            makeFolders()
            JsonlSink.appendLine(file, bytes, Long.MAX_VALUE, JsonlSink.NO_ARCHIVE, JsonlSink.PAGE_CACHE_FORCE)
        }
    }

    /** Make [file]'s parent, marking the folder believed to stand until an open says otherwise. */
    private fun makeFolders() {
        Files.createDirectories(file.parent)
        foldersMade = true
    }

    /** The uuid of the transcript's last line, which the next line follows; null for a new transcript. */
    private fun lastUuid(): String? {
        if (!Files.isRegularFile(file)) return null
        return JsonlSink.readTail(file, TAIL_BYTES).asReversed().firstNotNullOfOrNull { line ->
            JsonScalars.str(JsonScalars.objectOrNull(Json, line), "uuid")
        }
    }
}

/** Where Claude keeps transcripts, under its config folder. */
private const val TRANSCRIPTS_FOLDER = "projects"

/** How much of a transcript's end is read for its last line: far more than one line takes. */
private const val TAIL_BYTES = 4 * 1024 * 1024
