// NEW: save preparation and encoding refuse capacity before durable state changes.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStateWrite
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeSourceUsage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days

class CodeModeSaveBudgetTest(@param:TempDir private val dir: Path) {
    private val heap = HeapBudget(Long.MAX_VALUE, 512 * 1024)
    private var writes = 0
    private var refuseWrite = false
    private val location = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))
    private val store = CodexCodeModeStore(
        location,
        Json { encodeDefaults = true },
        {},
        writer = CodeModeStateWrite { path, text ->
            writes++
            if (refuseWrite) throw IOException("synthetic refused force")
            CodeModeStateJournal.write(path, text)
        },
        heap = heap,
    )

    private fun capacityRefused(block: () -> Unit) {
        val failure = assertThrows<Exception> { block() }
        assertTrue(
            failure is HeapCapacityException || failure.cause is HeapCapacityException,
            "only a demonstrated heap-capacity refusal satisfies this control",
        )
    }

    @Test
    fun `an oversized first snapshot is refused before any durable write`() {
        store.load()
        val record = CodeModeRecords.of("synthetic", 1).apply { source = "x".repeat(256 * 1024) }
        capacityRefused { store.save(listOf(record), emptyList()) }
        assertEquals(0, writes, "admission precedes snapshot encoding and its durable write")
        assertFalse(Files.exists(location.dir), "refusal creates no misleading durable admission")
    }

    @Test
    fun `escaped encoding pressure preserves the existing durable record`() {
        store.load()
        val record = CodeModeRecords.of("synthetic", 1)
        store.save(listOf(record), emptyList())
        val file = Files.list(location.dir).use { it.filter(Files::isRegularFile).findFirst().orElseThrow() }
        val before = Files.readAllBytes(file)
        val priorWrites = writes
        record.source = "\u0000".repeat(64 * 1024)
        capacityRefused {
            store.save(listOf(record), emptyList(), changedRecord = record)
        }
        assertEquals(priorWrites, writes, "UTF-8 and JSON escape growth remain behind capacity admission")
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun `a failed save retains its retry without writing an uncharged replacement`() {
        store.load()
        val record = CodeModeRecords.of("synthetic", 1)
        store.save(listOf(record), emptyList())
        record.output = "completion waiting for force"
        refuseWrite = true
        assertThrows<CodeModePersistenceException> {
            store.save(listOf(record), emptyList(), changedRecord = record)
        }
        refuseWrite = false
        val priorWrites = writes
        record.source = "x".repeat(256 * 1024)
        capacityRefused { store.save(listOf(record), emptyList(), retryOnly = true) }
        assertEquals(priorWrites, writes)
        assertTrue("synthetic" in store.pendingKeys, "capacity refusal must not erase the retry owner")
    }

    @Test
    fun `an escaped encoded string owns its text rather than the entire encoding peak`() {
        var escaped = ""
        val retaining = CodexCodeModeStore(
            location,
            Json { encodeDefaults = true },
            {},
            writer = CodeModeStateWrite { path, text ->
                escaped = text
                CodeModeStateJournal.write(path, text)
            },
            heap = heap,
        )
        val record = CodeModeRecords.of("synthetic", 1).apply { source = "x".repeat(2_000) }
        retaining.save(listOf(record), emptyList())
        assertTrue(escaped.contains(record.source))
        assertTrue(heap.available.value <= heap.limitBytes - HeapJson.text(escaped))
        assertTrue(
            heap.available.value > heap.limitBytes * 3 / 4,
            "field encodings and I/O buffers must not remain charged to the escaped string",
        )
        java.lang.ref.Reference.reachabilityFence(escaped)
    }

    @Test
    fun `checkpoint fallback is admitted before replacing an outgrown journal`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 4 * 1024 * 1024)
        var forcedWrites = 0
        val saving = CodexCodeModeStore(
            location,
            Json { encodeDefaults = true },
            {},
            writer = CodeModeStateWrite { path, text ->
                forcedWrites++
                CodeModeStateJournal.write(path, text)
            },
            heap = roomy,
        )
        val records = List(8) { index ->
            CodeModeRecords.of("synthetic", index).apply { source = "x".repeat(8_192) }
        }
        saving.save(records, emptyList())
        val file = Files.list(location.dir).use { it.filter(Files::isRegularFile).findFirst().orElseThrow() }
        // A sparse uncommitted tail crosses the real compaction floor without allocating a huge fixture string.
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.position(9L * 1024 * 1024 - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        val before = Files.size(file)
        val hold = checkNotNull(roomy.reserve(roomy.available.value - 512 * 1024))
        records.last().output = "completion"
        try {
            capacityRefused { saving.save(records, emptyList(), changedRecord = records.last()) }
            assertEquals(1, forcedWrites, "the full envelope must fit before its checkpoint replaces the file")
            assertEquals(before, Files.size(file))
            assertTrue("synthetic" in saving.pendingKeys)
        } finally {
            hold.close()
        }
        saving.save(records, emptyList(), retryOnly = true)
        assertEquals(2, forcedWrites)
        assertEquals("completion", CodeModeStateJournal.read(file, Json).records.last().output)
    }

    @Test
    fun `capacity refusal before snapshot preparation preserves proven unexecuted retry`() {
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record)
        try {
            registry.startup.failed(record)
            val hold = checkNotNull(heap.reserve(heap.available.value))
            try {
                capacityRefused { registry.startup.resume(record) }
                assertEquals(CodeModePhase.STARTING, record.phase, "an unexecuted source keeps its retry proof")
            } finally {
                hold.close()
            }
            assertEquals(true, registry.startup.resume(record))
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `capacity refusal before snapshot preparation cannot claim source usage`() {
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record)
        record.sourceState = CodeModeSourceState(complete = true, usage = CodeModeSourceUsage(1, 2, 0, 0, 0, 0))
        try {
            val hold = checkNotNull(heap.reserve(heap.available.value))
            try {
                capacityRefused { registry.source.consume(record) }
                assertEquals(false, record.sourceState?.consumed, "the failed force cannot consume billing")
            } finally {
                hold.close()
            }
            assertEquals(2L, registry.source.consume(record)?.outputTokens)
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    private fun registry(record: CodeModeRecord): CodexCodeModeRegistry {
        val config = CodeModeBridgeConfig(
            runtimes = { error("this reservation control must not start a worker") },
            state = location,
            clock = Clock.fixed(Instant.ofEpochMilli(record.updatedAt), ZoneOffset.UTC),
        )
        return CodexCodeModeRegistry(config, Json, 1.days, heap = heap).also {
            assertTrue(it.add(record))
        }
    }
}
