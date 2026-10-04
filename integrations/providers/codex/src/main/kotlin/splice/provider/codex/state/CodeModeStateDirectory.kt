// NEW: discovers versioned conversation journals and selects the current retained state.
// CREDENTIAL-WRITE-EXEMPT[2026-10-02]: corrupt code-mode conversation journals, never credentials.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Discovers conversation files and selects the current state across an upgrade and downgrade. */
internal class CodeModeStateDirectory(
    private val dir: Path,
    private val json: Json,
    private val log: LogSink,
) {
    data class Loaded(val state: CodeModePersistedState, val checkpoint: Boolean)

    private data class OnDisk(val path: Path, val key: String, val state: CodeModePersistedState, val liveBytes: Long) {
        val updatedAt: Long = maxOf(
            state.records.maxOfOrNull(CodeModeRecordSnapshot::updatedAt) ?: 0L,
            state.expired.maxOfOrNull(CodeModeExpiredSnapshot::expiredAt) ?: 0L,
        )
        val modifiedAt: Long = Files.getLastModifiedTime(path).toMillis()
    }

    fun load(): Map<String, Loaded> {
        if (!Files.isDirectory(dir)) return emptyMap()
        val files = try {
            Files.list(dir).use { entries -> entries.filter { FILE_NAME.matches(it.fileName.toString()) }.toList() }
        } catch (failure: IOException) {
            log("[code-mode] $dir not listed (${SafeFailureText.render(failure)}): its conversations are not restored")
            return emptyMap()
        }
        return files.mapNotNull(::read).groupBy(OnDisk::key).mapValues { (key, versions) ->
            // A downgraded jar can create newer .json state beside the .jsonl it cannot discover.
            val identical = versions.zipWithNext().all { (left, right) -> sameState(left.state, right.state) }
            val selected = versions.maxWith(
                compareBy<OnDisk> { it.updatedAt }
                    .thenBy { if (identical) 0L else it.modifiedAt }
                    .thenBy { it.path == path(key) },
            )
            val migration = versions.size > 1 || selected.path != path(key)
            Loaded(selected.state, migration || CodeModeStateJournal.outgrown(selected.path, selected.liveBytes))
        }
    }

    private fun sameState(left: CodeModePersistedState, right: CodeModePersistedState): Boolean =
        left == right && left.records.zip(right.records).all { (before, after) ->
            CodeModeStateJournal.same(before, after)
        }

    fun path(key: String): Path = named(key, ".jsonl")

    fun olderPath(key: String): Path = named(key, ".json")

    private fun named(key: String, suffix: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return dir.resolve(HexFormat.of().formatHex(digest) + suffix)
    }

    /** Invalid state is dropped alone. An I/O failure preserves the file for the next start. */
    private fun read(file: Path): OnDisk? {
        val why = try {
            val conversation = CodeModeStateJournal.read(file, json)
            val live = CodeModeStateJournal.liveBytes(conversation, json)
            val key = (conversation.records.map { it.key } + conversation.expired.map { it.key }).distinct()
                .singleOrNull()
            val namedKey = key?.takeIf { path(it) == file || olderPath(it) == file }
            if (namedKey != null) return OnDisk(file, namedKey, conversation, live)
            "it does not hold exactly the one conversation its name says"
        } catch (failure: IOException) {
            log(
                "[code-mode] conversation file ${file.fileName} not read " +
                    "(${SafeFailureText.render(failure)}): it stays",
            )
            return null
        } catch (_: IllegalArgumentException) {
            "it is not code-mode state"
        }
        log("[code-mode] conversation file ${file.fileName} unreadable ($why): removed, so its scripts are dropped")
        try {
            Files.deleteIfExists(file)
        } catch (failure: IOException) {
            log("[code-mode] ${file.fileName} not removed (${SafeFailureText.render(failure)})")
        }
        return null
    }
}

private val FILE_NAME = Regex("[0-9a-f]{64}\\.jsonl?")
