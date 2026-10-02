// NEW: append recovery never loses a committed cell and can still persist after a torn final delta.
package splice.provider.codex.v4340

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.minutes

private const val BLOATED_BYTES = 9L * 1024 * 1024
private const val MAX_COMPACTED_BYTES = 64L * 1024
private const val UPDATED_AT = 1_790_000_000_000L

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

    /** Oct 1: each step re-appended its whole cell and only a removal compacted, so one conversation's
     *  journal reached 2 GB over 4 MB of live cells and a 2 GB daemon died reading it at boot. */
    @Test
    fun `a journal far past its live cells is compacted when it loads`() {
        val record = CodeModeRecords.of("alpha", 1, UPDATED_AT)
        registry().run {
            assertTrue(add(record))
            complete(record, "done")
        }
        val file = bloated()
        registry()
        assertCompactedTo(file, "done")
    }

    @Test
    fun `a cell write compacts a journal that outgrew its cells while the head ran`() {
        val registry = registry()
        val record = CodeModeRecords.of("alpha", 1, UPDATED_AT)
        assertTrue(registry.add(record))
        registry.complete(record, "done")
        val file = bloated()
        registry.complete(record, "again")
        assertCompactedTo(file, "again")
    }

    @Test
    fun `a whole conversation save compacts a journal that outgrew its cells while the head ran`() {
        val store = CodexCodeModeStore(location(), Json { encodeDefaults = true }, {})
        store.load()
        val record = CodeModeRecords.of("alpha", 1, UPDATED_AT)
        store.save(listOf(record), emptyList())
        val file = bloated()
        record.output = "whole"
        store.save(listOf(record), emptyList())
        assertCompactedTo(file, "whole")
    }

    /** Re-appends the one conversation's cell until its journal passes the compaction floor, as each step of
     *  a long conversation did before compaction. */
    private fun bloated(): Path {
        val file = Files.list(location().dir).use { files ->
            files.toList().single { it.fileName.toString().endsWith(".json") }
        }
        val cell = CodeModeStateJournal.read(file, Json).records.single()
        val delta = Json.encodeToString(CodeModeStateDelta("alpha", listOf(cell), emptySet(), emptyList())) + "\n"
        Files.writeString(file, delta.repeat((BLOATED_BYTES / delta.length).toInt() + 1), StandardOpenOption.APPEND)
        assertTrue(Files.size(file) > BLOATED_BYTES)
        return file
    }

    /** [file] is one small checkpoint holding exactly these outputs, in record order. */
    private fun assertCompactedTo(file: Path, vararg outputs: String?) {
        assertTrue(Files.size(file) < MAX_COMPACTED_BYTES, "the journal stayed ${Files.size(file)} bytes")
        assertEquals(1, Files.readAllLines(file).count(String::isNotBlank), "a compacted journal is one checkpoint")
        assertEquals(outputs.toList(), CodeModeStateJournal.read(file, Json).records.map { it.output })
    }

    private fun location() = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))

    private fun registry() = CodexCodeModeRegistry(
        CodeModeBridgeConfig({ error("no script runs in a journal test") }, location()),
        Json { encodeDefaults = true },
        5.minutes,
    )

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
