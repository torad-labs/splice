// Moved out of CodeModeHistoryStorage.kt on Nov 10, 2026 (concentration), when the Nov 9 checkpoint rewrite grew that
// file: the one-time move of an intermediate full-snapshot history (`<digest>.json`) to its journal.
package splice.provider.codex.history.durable

import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.util.LogSink
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** An intermediate full-snapshot authority is forced to a staging journal before its name becomes live. */
internal class CodeModeHistoryMigration(
    private val paths: CodeModeHistoryPaths,
    private val json: Json,
    private val journal: CodeModeHistoryJournal,
    private val heap: HeapReservations,
    private val log: LogSink,
) {
    fun migrate(key: String): Pair<CodeModeHistoryState, CodeModeJournaled> {
        val old = paths.legacy(key)
        val live = paths.journal(key)
        val staging = live.resolveSibling("${live.fileName}.migrating")
        val migrated = HeapText.Reader.read(old, heap).use { stage ->
            val legacy = json.decodeFromString<CodeModeHistoryState>(stage.text)
            CodeModeHistoryValidation.legacy(legacy, key)
            entries(legacy, staging)
        }
        Files.move(staging, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(paths.directory, StandardOpenOption.READ).use { it.force(true) }
        Files.deleteIfExists(old)
        Files.deleteIfExists(paths.lock(staging))
        log("[code-mode] authoritative history migrated posts=${migrated.first.posts.size}")
        return migrated
    }

    private fun entries(old: CodeModeHistoryState, staging: Path): Pair<CodeModeHistoryState, CodeModeJournaled> {
        Files.deleteIfExists(staging)
        var state = CodeModeHistoryState(old.key, emptyList(), old.nextId)
        var bytes = 0L
        var digest: String? = null
        old.posts.sortedBy { it.id }.forEach { entry ->
            val next = state.copy(revision = state.revision + 1L)
            val prepared = CodeModeHistoryDeltas.prepare(next, entry, state.posts.firstOrNull())
            CodeModeHistoryDeltas.own(prepared, heap)
            val committed = checkNotNull(journal.append(staging, prepared.delta, bytes)) {
                "the history migration's staging file was removed while it was written"
            }
            bytes = committed.bytes
            digest = committed.digest
            state = next.copy(
                posts = listOf(prepared.entry) + state.posts,
                memoryBytes = state.memoryBytes + CodeModeHistoryDeltas.weight(prepared.delta),
            )
        }
        return state to CodeModeJournaled(state.revision, bytes, digest)
    }
}
