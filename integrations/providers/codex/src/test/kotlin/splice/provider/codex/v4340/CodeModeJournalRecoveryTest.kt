// NEW: append recovery never loses a committed cell and can still persist after a torn final delta.
package splice.provider.codex.v4340

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStateWrite
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val BLOATED_BYTES = 9L * 1024 * 1024
private const val MAX_COMPACTED_BYTES = 64L * 1024
private const val UPDATED_AT = 1_790_000_000_000L

// why: twice the child JVM's whole heap, so a loader that reads the file into memory cannot finish.
private const val LARGER_THAN_HEAP_BYTES = 64L * 1024 * 1024
private const val CHILD_HEAP = "-Xmx32m"

// why: one large cell, as a long tool result re-appended at each step makes one.
private const val DELTA_OUTPUT_CHARS = 64 * 1024

/** The streaming test's child JVM: reads one journal and prints how many cells it holds. */
internal object JournalReadProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println(CodeModeStateJournal.read(Path.of(args[0]), Json).records.size)
    }
}

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

    /** A full disk or a kill can cut a write inside a multi-byte character. The torn tail is dropped and
     *  the journal still loads; a strict decoder threw and the next save overwrote every cell. */
    @Test
    fun `a delta torn inside a multi-byte character keeps the committed checkpoint`() {
        val file = dir.resolve("torn-utf8.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        CodeModeStateJournal.write(file, Json.encodeToString(CodeModePersistedState(records = listOf(record))))
        val delta = Json.encodeToString(
            CodeModeStateDelta("alpha", listOf(record.copy(output = "café")), emptySet(), emptyList()),
        ).encodeToByteArray()
        val tear = delta.indexOfFirst { it == 0xC3.toByte() } + 1
        Files.write(file, delta.copyOf(tear), StandardOpenOption.APPEND)
        assertEquals(record.id, CodeModeStateJournal.read(file, Json).records.single().id)
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
     *  journal reached 2 GB over 4 MB of live cells and a 2 GB daemon died reading it at boot. Each case
     *  holds two cells and an expiry marker, so a compaction that drops either fails. */
    @Test
    fun `a journal far past its live cells is compacted when it loads`() {
        val (_, file) = bloatedConversation()
        store().load()
        assertCompacted(file, listOf(null, "kept"))
    }

    @Test
    fun `a cell write compacts a journal that outgrew its cells while the head ran`() {
        val (store, file) = bloatedConversation()
        first.output = "again"
        store.save(listOf(first, second), listOf(marker), dirtyKeys = setOf("alpha"), changedRecord = first)
        assertCompacted(file, listOf("again", "kept"))
    }

    @Test
    fun `a whole conversation save compacts a journal that outgrew its cells while the head ran`() {
        val (store, file) = bloatedConversation()
        first.output = "whole"
        store.save(listOf(first, second), listOf(marker))
        assertCompacted(file, listOf("whole", "kept"))
    }

    /** A client may send an unpaired surrogate in a tool result. An append writes it as `?`; a
     *  compaction must too, or every later save of an outgrown conversation fails. */
    @Test
    fun `an outgrown journal holding an unpaired surrogate compacts as an append writes it`() {
        val (store, file) = bloatedConversation()
        first.output = "ok" + Char(0xD83D)
        store.save(listOf(first, second), listOf(marker), dirtyKeys = setOf("alpha"), changedRecord = first)
        assertCompacted(file, listOf("ok?", "kept"))
    }

    /** A compaction that cannot be written at load leaves the journal as it was and still loads its
     *  cells; dropping the conversation there would let its next save overwrite it. */
    @Test
    fun `a compaction that fails at load keeps the conversation and its journal`() {
        val (_, file) = bloatedConversation()
        val before = Files.readAllBytes(file)
        val refusing = CodeModeStateWrite { path, text ->
            if (text.startsWith("{\"key\":")) CodeModeStateJournal.write(path, text) else throw IOException("refused")
        }
        val loaded = CodexCodeModeStore(location(), Json { encodeDefaults = true }, {}, writer = refusing).load()
        assertEquals(listOf(first.id, second.id), loaded.records.map { it.id })
        assertEquals(listOf(null, "kept"), loaded.records.map { it.output })
        assertEquals(listOf(marker), loaded.expired)
        assertTrue(before.contentEquals(Files.readAllBytes(file)), "a compaction that failed changed the journal")
    }

    /** Loading streams the journal a line at a time: a journal twice the size of a child JVM's whole
     *  heap loads there, where reading the file into memory cannot. */
    @Test
    @Timeout(120)
    fun `a journal larger than the whole heap loads a line at a time`() {
        val file = dir.resolve("larger-than-heap.json")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        CodeModeStateJournal.write(file, Json.encodeToString(CodeModePersistedState(records = listOf(record))))
        val cell = record.copy(output = "x".repeat(DELTA_OUTPUT_CHARS))
        val delta = Json.encodeToString(CodeModeStateDelta("alpha", listOf(cell), emptySet(), emptyList())) + "\n"
        Files.newBufferedWriter(file, StandardOpenOption.APPEND).use { out ->
            repeat((LARGER_THAN_HEAP_BYTES / delta.length).toInt() + 1) { out.write(delta) }
        }
        val classpath = checkNotNull(System.getProperty("codex.testClasspath"))
        val java = "${System.getProperty("java.home")}/bin/java"
        val child = ProcessBuilder(
            java,
            CHILD_HEAP,
            "-cp",
            classpath,
            JournalReadProbe::class.java.name,
            file.toString(),
        ).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText()
        assertEquals(0, child.waitFor(), output)
        assertEquals("1", output.trim())
    }

    private val first = CodeModeRecords.of("alpha", 1, UPDATED_AT)
    private val second = CodeModeRecords.of("alpha", 2, UPDATED_AT + 1).apply { output = "kept" }
    private val marker = CodeModeExpiredSnapshot("alpha", "digest-gone", setOf("gone-result"), UPDATED_AT - 1)

    /** A store holding [first], [second] and [marker], whose journal then re-appends [first]'s cell until
     *  it passes the compaction floor, as each step of a long conversation did before compaction. */
    private fun bloatedConversation(): Pair<CodexCodeModeStore, Path> {
        val store = store().also { it.load() }
        store.save(listOf(first, second), listOf(marker))
        val file = Files.list(location().dir).use { files ->
            files.toList().single { it.fileName.toString().endsWith(".json") }
        }
        val cell = CodeModeStateJournal.read(file, Json).records.first()
        val delta = Json.encodeToString(CodeModeStateDelta("alpha", listOf(cell), emptySet(), listOf(marker))) + "\n"
        Files.writeString(file, delta.repeat((BLOATED_BYTES / delta.length).toInt() + 1), StandardOpenOption.APPEND)
        assertTrue(Files.size(file) > BLOATED_BYTES)
        return store to file
    }

    /** [file] is one small checkpoint holding both cells, with these outputs in order, and the marker. */
    private fun assertCompacted(file: Path, outputs: List<String?>) {
        assertTrue(Files.size(file) < MAX_COMPACTED_BYTES, "the journal stayed ${Files.size(file)} bytes")
        assertEquals(1, Files.readAllLines(file).count(String::isNotBlank), "a compacted journal is one checkpoint")
        val state = CodeModeStateJournal.read(file, Json)
        assertEquals(listOf(first.id, second.id), state.records.map { it.id })
        assertEquals(outputs, state.records.map { it.output })
        assertEquals(listOf(marker), state.expired)
    }

    private fun location() = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))

    private fun store() = CodexCodeModeStore(location(), Json { encodeDefaults = true }, {})

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
