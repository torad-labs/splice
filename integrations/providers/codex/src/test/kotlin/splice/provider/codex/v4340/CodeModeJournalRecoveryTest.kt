// NEW: append recovery never loses a committed cell and can still persist after a torn final delta.
package splice.provider.codex.v4340

import com.sun.management.ThreadMXBean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStateWrite
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.CodeModeStateText
import java.io.IOException
import java.lang.management.ManagementFactory
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
    private val codec = Json { encodeDefaults = true }

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a source-only save neither rewrites nor reencodes a large root history`(reporter: TestReporter) {
        val root = CodeModeRecords.of("alpha", 1).apply {
            nativeSegments = listOf(CodeModeNativeSegment(0, listOf(JsonPrimitive("x".repeat(1024 * 1024)))))
        }
        val store = store().also { it.load() }
        store.save(listOf(root), emptyList())
        val file = Files.list(location().dir).use { it.toList().single { path -> path.toString().endsWith(".jsonl") } }
        // Warm the append implementation and coverage instrumentation, not the measured source change.
        root.output = "warm append"
        store.save(listOf(root), emptyList(), dirtyKeys = setOf(root.key), changedRecord = root)
        root.output = null
        store.save(listOf(root), emptyList(), dirtyKeys = setOf(root.key), changedRecord = root)
        val beforeBytes = Files.size(file)
        val bean = checkNotNull(ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        root.source += "; next source fragment"
        val beforeAllocated = bean.getThreadAllocatedBytes(thread)
        store.save(listOf(root), emptyList(), dirtyKeys = setOf(root.key), changedRecord = root)
        val allocated = bean.getThreadAllocatedBytes(thread) - beforeAllocated
        val written = Files.size(file) - beforeBytes
        val measured = mapOf("sourceSaveBytes" to written.toString(), "sourceSaveAllocated" to allocated.toString())
        reporter.publishEntry(measured)
        val receipt = Path.of("build/reports/source-save-cost.json")
        Files.createDirectories(receipt.parent)
        Files.writeString(receipt, "{\"bytesWritten\":$written,\"bytesAllocated\":$allocated}\n")
        assertTrue(written < 32 * 1024, "source-only save wrote $written bytes and allocated $allocated bytes")
        assertTrue(allocated < 512 * 1024, "source-only save allocated $allocated bytes for a 1 MiB unchanged history")
        val restored = CodeModeStateJournal.read(file, Json).records.single()
        assertEquals(root.source, restored.source)
        assertEquals(root.nativeSegments, restored.nativeSegments)
    }

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

    /** Every serialized field except immutable cell and conversation identities earns a replacement. */
    private fun populated(base: CodeModeRecordSnapshot): CodeModeRecordSnapshot {
        val fields = codec.encodeToJsonElement(base).jsonObject
        val values = codec.parseToJsonElement(
            """
            {
              "outer":{"synthetic":"payload"},"outerCallId":"changed-call","source":"changed source",
              "phase":"COMPLETED","pending":[{"runtimeId":"r","clientId":"c","name":"Read",
              "arguments":{"synthetic":true},"exposed":true}],
              "results":{"r":{"output":"synthetic result","isError":true,"media":["synthetic media"]}},
              "output":"synthetic output","error":"synthetic error","totalCalls":71,"rounds":72,
              "updatedAt":73,"lastDigest":"changed digest","baselineInputCount":74,
              "baselineInputDigest":"changed input","metadataVersion":75,"baselineLogicalCount":76,
              "baselineLogicalDigest":"changed logical","nativeSegments":[{"logicalOffset":77,"items":["native"]}],
              "continuity":["logical"],"continuityReplay":[{"logicalOffset":78,"items":["replay"]}],
              "issued":[{"requestDigest":"issued digest","calls":[]}],"sessionId":"synthetic session",
              "nativeBaseId":"synthetic parent","replayAnchors":{"baseline":{"itemDigest":"anchor","occurrence":79},
              "native":{}},"sourceState":{"complete":true,"consumed":true}
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(fields.keys - setOf("id", "key"), values.keys, "fixture covers the serializer's field denominator")
        assertTrue(values.all { (name, value) -> fields[name] != value }, "each fixture field must actually differ")
        return codec.decodeFromJsonElement(JsonObject(fields + values))
    }

    @Test
    fun `patches replace every serialized field including defaults empty collections and nullable body state`() {
        val file = dir.resolve("all-fields.json")
        val empty = CodeModeRecords.of("alpha", 1).snapshot()
        val full = populated(empty)
        val prior = CodeModePersistedState(records = listOf(full))
        CodeModeStateJournal.write(file, CodeModeStateJournal.encode("alpha", null, prior, codec))
        val next = CodeModePersistedState(records = listOf(empty))
        val patch = CodeModeStateJournal.encode("alpha", prior, next, codec, file)
        val fields = codec.parseToJsonElement(patch).jsonObject.getValue("patches")
            .jsonArray.single().jsonObject.getValue("fields").jsonObject
        assertEquals(codec.encodeToJsonElement(full).jsonObject.keys - setOf("id", "key"), fields.keys)
        CodeModeStateJournal.write(file, patch)
        assertEquals(codec.encodeToJsonElement(next), codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec)))
        assertEquals(codec.encodeToString(empty).toByteArray(Charsets.UTF_8).size.toLong(), empty.retainedBytes)
    }

    @Test
    fun `older full-cell readers reject patch rows even when unknown fields are ignored`() {
        val prior = CodeModePersistedState(records = listOf(CodeModeRecords.of("alpha", 1).snapshot()))
        val next = CodeModePersistedState(records = listOf(prior.records.single().copy(source = "new source")))
        val patch = CodeModeStateJournal.encode("alpha", prior, next, codec)
        val oldReader = Json { ignoreUnknownKeys = true }
        assertThrows<IllegalArgumentException> {
            assertEquals("alpha", oldReader.decodeFromString<CodeModeStateDelta>(patch).key)
        }
    }

    @Test
    fun `an old full-cell delta can be followed by patches without losing its heavy fields`() {
        val file = dir.resolve("mixed-journal.json")
        val original = populated(CodeModeRecords.of("alpha", 1).snapshot())
        CodeModeStateJournal.write(file, pretty.encodeToString(CodeModePersistedState(records = listOf(original))))
        val old = original.copy(source = "legacy source")
        val delta = CodeModeStateDelta("alpha", listOf(old), emptySet(), emptyList())
        CodeModeStateJournal.write(file, codec.encodeToString(delta))
        val prior = CodeModeStateJournal.read(file, codec)
        CodeModeStateJournal.liveBytes(prior, codec)
        val changed = prior.records.single().copy(source = "patch source").also {
            it.issued = original.issued
            it.sessionId = original.sessionId
            it.nativeBaseId = original.nativeBaseId
            it.replayAnchors = original.replayAnchors
            it.sourceState = original.sourceState
        }
        val next = CodeModePersistedState(records = listOf(changed))
        CodeModeStateJournal.write(file, CodeModeStateJournal.encode("alpha", prior, next, codec, file))
        assertEquals(codec.encodeToJsonElement(next), codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec)))
    }

    @Test
    fun `every torn patch prefix remains uncommitted and a retry preserves all base fields`() {
        val original = populated(CodeModeRecords.of("alpha", 1).snapshot())
        val prior = CodeModePersistedState(records = listOf(original))
        val checkpoint = CodeModeStateJournal.encode("alpha", null, prior, codec)
        val next = CodeModePersistedState(records = listOf(original.copy(output = "café")))
        val patch = CodeModeStateJournal.encode("alpha", prior, next, codec)
        patch.indices.drop(1).forEach { prefix ->
            val file = dir.resolve("patch-tear-$prefix.json")
            CodeModeStateJournal.write(file, checkpoint)
            Files.writeString(file, patch.take(prefix), StandardOpenOption.APPEND)
            val loaded = codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec))
            assertEquals(codec.encodeToJsonElement(prior), loaded)
            CodeModeStateJournal.write(file, patch)
            val retried = codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec))
            assertEquals(codec.encodeToJsonElement(next), retried)
        }
    }

    @Test
    fun `a patch torn inside UTF-8 keeps its base and retry commits the complete field`() {
        val file = dir.resolve("patch-utf8.json")
        val original = CodeModeRecords.of("alpha", 1).snapshot()
        val prior = CodeModePersistedState(records = listOf(original))
        CodeModeStateJournal.write(file, CodeModeStateJournal.encode("alpha", null, prior, codec))
        val next = CodeModePersistedState(records = listOf(original.copy(output = "café")))
        val patch = CodeModeStateJournal.encode("alpha", prior, next, codec)
        val bytes = patch.toByteArray(Charsets.UTF_8)
        val tear = bytes.indexOfFirst { it == 0xC3.toByte() } + 1
        Files.write(file, bytes.copyOf(tear), StandardOpenOption.APPEND)
        val loaded = codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec))
        assertEquals(codec.encodeToJsonElement(prior), loaded)
        CodeModeStateJournal.write(file, patch)
        assertEquals("café", CodeModeStateJournal.read(file, codec).records.single().output)
    }

    @Test
    fun `a new patch cell missing only defaulted source state has no complete base`() {
        val original = CodeModeRecords.of("alpha", 1).snapshot()
        val inserted = CodeModeRecords.of("alpha", 2).snapshot()
        val fields = JsonObject(codec.encodeToJsonElement(inserted).jsonObject - "sourceState")
        // The serializer accepts this row; only the patch completeness boundary can reject it.
        assertEquals(inserted.id, codec.decodeFromJsonElement<CodeModeRecordSnapshot>(fields).id)
        val file = dir.resolve("default-insertion.json")
        CodeModeStateJournal.write(file, codec.encodeToString(CodeModePersistedState(records = listOf(original))))
        val cell = """{"id":"${inserted.id}","fields":$fields}"""
        CodeModeStateJournal.write(file, """{"key":"alpha","patches":[$cell],"expired":[]}""")
        val failure = assertThrows<IllegalArgumentException> { CodeModeStateJournal.read(file, codec) }
        assertEquals("code-mode journal patch has no complete base cell", failure.message)
    }

    @Test
    fun `checkpoint forces its temporary file before replacement and its directory afterward`() {
        val file = dir.resolve("forced-checkpoint.json")
        Files.writeString(file, "old checkpoint")
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        val text = codec.encodeToString(CodeModePersistedState(records = listOf(record)))
        val events = mutableListOf<String>()
        CodeModeStateJournal.write(file, text) { target, channel ->
            if (Files.isDirectory(target)) {
                assertEquals(dir.toAbsolutePath(), target)
                assertEquals(text + "\n", Files.readString(file), "directory force must follow the atomic replacement")
                events += "directory"
            } else {
                assertFalse(target == file, "file force must use the temporary file, never the replaced path")
                assertEquals("old checkpoint", Files.readString(file), "replacement must follow the file force")
                assertEquals(text + "\n", Files.readString(target))
                events += "file"
            }
            channel.force(true)
        }
        assertEquals(listOf("file", "directory"), events)
    }

    @Test
    fun `new patch cells require all descriptor fields and identity changes fail loudly`() {
        val original = CodeModeRecords.of("alpha", 1).snapshot()
        val next = CodeModeRecords.of("alpha", 2).snapshot()
        val file = dir.resolve("insert.json")
        val prior = CodeModePersistedState(records = listOf(original))
        val checkpoint = CodeModeStateJournal.encode("alpha", null, prior, codec)
        CodeModeStateJournal.write(file, checkpoint)
        val state = CodeModePersistedState(records = listOf(original, next))
        val patch = CodeModeStateJournal.encode("alpha", prior, state, codec, file)
        CodeModeStateJournal.write(file, patch)
        val loaded = codec.encodeToJsonElement(CodeModeStateJournal.read(file, codec))
        assertEquals(codec.encodeToJsonElement(state), loaded)
        listOf(
            """{"id":"${next.id}","fields":{"id":"${next.id}","key":"alpha","source":"truncated"}}""",
            """{"id":"${original.id}","fields":{"id":"other"}}""",
            """{"id":"${original.id}","fields":{"key":"other"}}""",
        ).forEach { cell ->
            CodeModeStateJournal.write(file, checkpoint)
            CodeModeStateJournal.write(file, """{"key":"alpha","patches":[$cell],"expired":[]}""")
            assertThrows<IllegalArgumentException> { CodeModeStateJournal.read(file, codec) }
        }
    }

    @Test
    fun `changed-cell recovery recreates a missing file with every live cell and expiry marker`() {
        val store = store().also { it.load() }
        store.save(listOf(first, second), listOf(marker))
        val file = Files.list(location().dir).use { it.toList().single() }
        Files.delete(file)
        first.source = "recovered source"
        store.save(listOf(first, second), listOf(marker), dirtyKeys = setOf("alpha"), changedRecord = first)
        assertEquals(listOf(first.id, second.id), CodeModeStateJournal.read(file, codec).records.map { it.id })
        assertEquals(listOf(marker), CodeModeStateJournal.read(file, codec).expired)
        assertEquals(1, Files.readAllLines(file).size)
    }

    @Test
    fun `retained byte accounting matches actual UTF-8 for every character width and malformed surrogates`() {
        listOf("ascii", "café", "你好", "😀", "a\uD83Db", "\uDC00").forEach { text ->
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), CodeModeStateText(text).bytes)
            val record = CodeModeRecords.of("alpha", 1).snapshot().copy(output = text)
            CodeModeStateJournal.liveBytes(CodeModePersistedState(records = listOf(record)), codec)
            assertEquals(codec.encodeToString(record).toByteArray(Charsets.UTF_8).size.toLong(), record.retainedBytes)
        }
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
            files.toList().single { it.fileName.toString().endsWith(".jsonl") }
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

class CodeModeJournalIsolationTest {
    private val codec = Json { encodeDefaults = true }

    @TempDir
    lateinit var dir: Path

    private data class Conversation(
        val store: CodexCodeModeStore,
        val location: CodeModeStateLocation,
        val records: List<splice.provider.codex.CodeModeRecord>,
        val file: Path,
        val healthy: Path,
    )

    @Test
    fun `a corrupt committed checkpoint cannot abort recovery of another conversation`() {
        corruptCells().forEachIndexed { index, cell ->
            val fixture = corruptConversation(index, cell)
            val recovered = CodexCodeModeStore(fixture.location, codec, {}).load()
            assertEquals(listOf(fixture.records.last().id), recovered.records.map { it.id }, "corrupt base $index")
            assertFalse(Files.exists(fixture.file), "only the malformed conversation is dropped")
            assertTrue(Files.exists(fixture.healthy))
        }
    }

    @Test
    fun `a live append over corrupt committed state and a torn tail fails only that conversation`() {
        corruptCells().forEachIndexed { index, cell ->
            val fixture = corruptConversation(index, cell)
            Files.writeString(fixture.file, """{"key":""", StandardOpenOption.APPEND)
            val before = Files.readString(fixture.file)
            val first = fixture.records.first().apply { output = "new alpha output" }
            val failure = assertThrows<CodeModePersistenceException> {
                fixture.store.save(fixture.records, emptyList(), dirtyKeys = setOf(first.key), changedRecord = first)
            }
            assertTrue(failure.cause is IOException)
            assertTrue(failure.cause?.cause is IllegalArgumentException)
            assertEquals("code-mode journal has corrupt committed state", failure.cause?.message)
            assertEquals(before, Files.readString(fixture.file), "corrupt committed bytes must not be appended")
            assertEquals(setOf(first.key), fixture.store.pendingKeys)
            val second = fixture.records.last().apply { output = "healthy beta output" }
            fixture.store.save(fixture.records, emptyList(), dirtyKeys = setOf(second.key), changedRecord = second)
            assertEquals(second.output, CodeModeStateJournal.read(fixture.healthy, codec).records.single().output)
            // The failed append marks only alpha uncertain; its explicit retry replaces the complete state.
            fixture.store.save(fixture.records, emptyList(), dirtyKeys = setOf(first.key), changedRecord = first)
            assertTrue(fixture.store.pendingKeys.isEmpty())
            val recovered = CodexCodeModeStore(fixture.location, codec, {}).load()
            assertEquals(fixture.records.map { it.output }.toSet(), recovered.records.map { it.output }.toSet())
        }
    }

    private fun corruptCells(): List<JsonObject?> {
        val complete = codec.encodeToJsonElement(CodeModeRecords.of("alpha", 1).snapshot()).jsonObject
        return listOf(JsonObject(complete - "id"), JsonObject(complete + ("id" to JsonNull)), null)
    }

    private fun corruptConversation(index: Int, cell: JsonObject?): Conversation {
        val records = listOf(CodeModeRecords.of("alpha", 1), CodeModeRecords.of("beta", 1))
        val location = CodeModeStateLocation(dir.resolve("corrupt-$index"), dir.resolve("legacy-$index.json"))
        val store = CodexCodeModeStore(location, codec, {}).also { it.load() }
        store.save(records, emptyList())
        val files = Files.list(location.dir).use { paths ->
            paths.toList().associateBy { CodeModeStateJournal.read(it, codec).records.single().key }
        }
        val checkpoint = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "records" to JsonArray(cell?.let { listOf(it) }.orEmpty()),
                "expired" to JsonArray(emptyList()),
            ),
        )
        val file = files.getValue("alpha")
        Files.writeString(
            file,
            checkpoint.toString() + "\n" + """{"key":"alpha","patches":[],"expired":[]}""" + "\n",
        )
        return Conversation(store, location, records, file, files.getValue("beta"))
    }
}
