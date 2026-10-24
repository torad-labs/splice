// NEW: a code-mode save writes the conversation that changed, not the whole head. The store
// rewrote one file holding every record on each save: live on 2026-09-26 that file was 8.8 MB for 128
// records, rewritten several times per script round, so a save's cost grew with every other conversation
// on the head. What is pinned here does not name the layout: after a head has held several conversations,
// one more script round of ONE of them changes exactly one file on disk, and that file holds that
// conversation's records and no other's.
package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeStateJournal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.time.Duration.Companion.minutes

private const val OTHER_CONVERSATIONS = 6
private val LONG_AGO = FileTime.fromMillis(0)

class CodeModeSaveScopeTest {

    @TempDir
    lateinit var tempDir: Path

    private val registry by lazy {
        CodexCodeModeRegistry(
            CodeModeBridgeConfig(
                { error("no script runs in a registry test") },
                CodeModeStateLocation(tempDir.resolve("state"), tempDir.resolve("state.json")),
            ),
            Json { encodeDefaults = true },
            5.minutes,
        )
    }

    private fun script(conversation: Int, n: Int): CodeModeRecord {
        val updatedAt = 1_790_000_000_000L + conversation * 1_000L + n
        val record = CodeModeRecords.of("conversation-$conversation", n, updatedAt)
        assertTrue(registry.add(record), "script $n of conversation $conversation was refused")
        registry.complete(record, "done $conversation/$n")
        return record
    }

    /** Every regular file under the test's directory: whatever the store keeps, wherever it keeps it. */
    private fun files(): List<Path> = Files.walk(tempDir).use { walk ->
        walk.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".lock") }.toList()
    }

    /** The conversation keys of the records a file holds: the store's file has a `records` array. */
    private fun keysIn(file: Path): Set<String> =
        CodeModeStateJournal.read(file, Json).records.map { it.key }.toSet()

    @Test
    fun `a script round of one conversation changes one file, and that file holds only its records`() {
        (1..OTHER_CONVERSATIONS).forEach { other -> repeat(2) { n -> script(other, n) } }
        files().forEach { Files.setLastModifiedTime(it, LONG_AGO) }

        script(conversation = 3, n = 9)

        val changed = files().filter { Files.getLastModifiedTime(it) != LONG_AGO }
        assertEquals(1, changed.size, "the round rewrote ${changed.map { it.fileName }}, not one file")
        assertEquals(
            setOf("conversation-3"),
            keysIn(changed.single()),
            "the file the round wrote holds records of other conversations",
        )
    }
}
