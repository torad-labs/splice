// NEW: append recovery never loses a committed cell and can still persist after a torn final delta.
package splice.provider.codex.v4340

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CodeModeJournalRecoveryTest {
    private val pretty = Json { prettyPrint = true }

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a legacy formatted checkpoint accepts new cell deltas`() {
        val file = dir.resolve("journal.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        Files.writeString(file, pretty.encodeToString(CodeModePersistedState(records = listOf(record))))
        val updated = record.copy(output = "new")
        val delta = CodeModeStateDelta("alpha", listOf(updated), emptySet(), emptyList())
        CodeModeStateJournal.write(file, Json.encodeToString(delta))
        assertEquals("new", CodeModeStateJournal.read(file, Json).records.single().output)
    }

    @Test
    fun `a torn final delta leaves earlier durable state readable and a retry can append`() {
        val file = dir.resolve("journal.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        val checkpoint = CodeModePersistedState(records = listOf(record))
        CodeModeStateJournal.write(file, Json.encodeToString(checkpoint))
        Files.writeString(file, """{"key":"alpha","records":[""", StandardOpenOption.APPEND)
        assertEquals(record.id, CodeModeStateJournal.read(file, Json).records.single().id)

        val updated = record.copy(output = "after recovery")
        val delta = CodeModeStateDelta("alpha", listOf(updated), emptySet(), emptyList())
        CodeModeStateJournal.write(file, Json.encodeToString(delta))
        assertEquals("after recovery", CodeModeStateJournal.read(file, Json).records.single().output)
    }

    @Test
    fun `every prefix of a torn first delta keeps the committed checkpoint`() {
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        val checkpoint = CodeModePersistedState(records = listOf(record))
        val delta = Json.encodeToString(
            CodeModeStateDelta("alpha", listOf(record.copy(output = "new")), emptySet(), emptyList()),
        )
        listOf(Json.encodeToString(checkpoint), pretty.encodeToString(checkpoint)).forEachIndexed { layout, text ->
            delta.indices.drop(1).forEach { prefix ->
                val file = dir.resolve("tear-$layout-$prefix.json")
                CodeModeStateJournal.write(file, text)
                Files.writeString(file, delta.take(prefix), StandardOpenOption.APPEND)
                assertEquals(record.id, CodeModeStateJournal.read(file, Json).records.single().id, "prefix $prefix")
                CodeModeStateJournal.write(file, delta)
                assertEquals("new", CodeModeStateJournal.read(file, Json).records.single().output, "retry $prefix")
            }
        }
    }

    @Test
    fun `an uncommitted tail never hides a corrupt committed entry`() {
        val file = dir.resolve("corrupt.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        CodeModeStateJournal.write(file, Json.encodeToString(CodeModePersistedState(records = listOf(record))))
        CodeModeStateJournal.write(
            file,
            Json.encodeToString(CodeModeStateDelta("alpha", listOf(record), emptySet(), emptyList())),
        )
        Files.writeString(file, "{\"key\":invalid}\n{", StandardOpenOption.APPEND)
        assertThrows<IllegalArgumentException> { CodeModeStateJournal.read(file, Json) }
    }

    @Test
    fun `a delta cannot become a checkpoint when the conversation file disappeared`() {
        val file = dir.resolve("missing.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        val delta = CodeModeStateDelta("alpha", listOf(record), emptySet(), emptyList())
        assertThrows<IOException> { CodeModeStateJournal.write(file, Json.encodeToString(delta)) }
        assertFalse(Files.exists(file), "a retry must recreate the whole committed checkpoint first")
    }

    @Test
    fun `committed deletion purges prior payload bytes and does not resurrect its cell`() {
        val file = dir.resolve("journal.json")
        val first = CodeModeRecords.of("alpha", 1).snapshot().copy(output = "private-expired-payload")
        val second = CodeModeRecords.of("alpha", 2).snapshot()
        val checkpoint = CodeModePersistedState(records = listOf(first, second))
        CodeModeStateJournal.write(file, Json.encodeToString(checkpoint))
        val next = CodeModePersistedState(records = listOf(second))
        CodeModeStateJournal.write(file, CodeModeStateJournal.encode("alpha", checkpoint, next, Json))
        assertEquals(listOf(second.id), CodeModeStateJournal.read(file, Json).records.map { it.id })
        assertFalse(Files.readString(file).contains("private-expired-payload"))
    }
}
