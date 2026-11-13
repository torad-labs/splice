// NEW: #525 (2026-11-13): what a retiring daemon tells the next one of the conversations it lets go, so a start does
// not read every journal to learn what the retiring daemon already knew.
package splice.provider.codex.state.handover

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.provider.codex.CodeModeExpiredSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What a head keeps of a conversation whose records are in its journal alone: when it was last used, how many of
 *  the journal's bytes are live, which client conversations it answers, and its expiry markers. */
@Serializable
internal data class CodeModeColdEntry(
    val updatedAt: Long,
    val liveBytes: Long,
    val conversations: Set<String>,
    val expired: List<CodeModeExpiredSnapshot>,
)

/** One conversation let go at a retire, with the journal it was told of: the file's name, size, time and node. A
 *  journal written or replaced since differs in one of them. */
@Serializable
internal data class CodeModeLetGo(
    val key: String,
    val file: String,
    val bytes: Long,
    val modifiedAt: Long,
    val node: String?,
    val entry: CodeModeColdEntry,
)

/**
 * The list a retiring daemon leaves in a head's code-mode folder. A start read every journal whole to learn four
 * small things of each conversation, and then kept most of them on the disk alone anyway: on Nov 13, 2026, 676 MiB
 * in 82 journals held one command 5.8 s of the 8.3 s a restart paused it. The retiring daemon knows those things, so
 * it lists them here once it has let the conversations go, and the next start takes a conversation from the list
 * where its journal is still the file the list names. Every other journal is read as before: one the list does not
 * name, one written since, and all of them after a stop that was not a retire.
 */
internal class CodeModeLetGoFile(private val dir: Path, private val json: Json, private val log: LogSink) {
    @Serializable
    private data class Told(val version: Int, val conversations: List<CodeModeLetGo>)

    /** [key]'s line of the list, with its [journal] as the disk holds it now; null when the journal cannot be read. */
    fun of(key: String, journal: Path, entry: CodeModeColdEntry): CodeModeLetGo? = try {
        CodeModeLetGo(
            key,
            journal.fileName.toString(),
            Files.size(journal),
            Files.getLastModifiedTime(journal).to(TimeUnit.NANOSECONDS),
            Files.getAttribute(journal, "fileKey")?.toString(),
            entry,
        )
    } catch (_: IOException) {
        null
    }

    /** Replaces the list. A list that cannot be written costs the next start the read of these journals. */
    fun write(conversations: List<CodeModeLetGo>) {
        try {
            SecureFile.writeAtomic0600(
                dir.resolve(NAME),
                json.encodeToString(Told.serializer(), Told(VERSION, conversations)),
            )
        } catch (failure: IOException) {
            log(
                "[code-mode] the conversations let go were not listed (${SafeFailureText.render(failure)}): " +
                    "the next start reads their journals",
            )
        }
    }

    /** Of [journals], each one the list names that is still the file it names. */
    fun told(journals: Collection<Path>): Map<Path, CodeModeLetGo> {
        val listed = read().associateBy(CodeModeLetGo::file)
        if (listed.isEmpty()) return emptyMap()
        return journals.mapNotNull { journal ->
            val told = listed[journal.fileName.toString()]
            if (told != null && of(told.key, journal, told.entry) == told) journal to told else null
        }.toMap()
    }

    private fun read(): List<CodeModeLetGo> {
        val file = dir.resolve(NAME)
        val why = try {
            if (Files.notExists(file)) return emptyList()
            val told = json.decodeFromString(Told.serializer(), Files.readString(file))
            if (told.version == VERSION) return told.conversations
            "it is of version ${told.version}"
        } catch (failure: IOException) {
            SafeFailureText.render(failure)
        } catch (failure: IllegalArgumentException) {
            SafeFailureText.render(failure)
        }
        log("[code-mode] ${file.fileName} not used ($why): this start reads every journal")
        return emptyList()
    }
}

// why: every other file of the folder is named by a conversation's hash, so this name is never one of them.
private const val NAME = "let-go.json"

// why: a list written in another shape is not read, and its conversations are read from their journals.
private const val VERSION = 1
