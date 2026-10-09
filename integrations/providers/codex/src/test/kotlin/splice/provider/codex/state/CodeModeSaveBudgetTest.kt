// NEW: save preparation and encoding refuse capacity before durable state changes.
package splice.provider.codex.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeIssuedStep
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateFiles
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStateWrite
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeSourceUsage
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
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
import kotlin.time.Duration.Companion.milliseconds

class CodeModeSaveBudgetTest(@param:TempDir private val dir: Path) {
    private val heap = HeapBudget(Long.MAX_VALUE, 512 * 1024)
    private var writes = 0
    private var refuseWrite = false
    private val location = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))
    private val writer = CodeModeStateWrite { path, text ->
        writes++
        if (refuseWrite) throw IOException("synthetic refused force")
        CodeModeStateJournal.write(path, text)
    }
    private val store = CodexCodeModeStore(
        location,
        Json { encodeDefaults = true },
        {},
        writer = writer,
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
        val record = CodeModeRecords.of("synthetic", 1).apply { origin.source = "x".repeat(256 * 1024) }
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
        record.origin.source = "\u0000".repeat(64 * 1024)
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
        record.progress.output = "completion waiting for force"
        refuseWrite = true
        assertThrows<CodeModePersistenceException> {
            store.save(listOf(record), emptyList(), changedRecord = record)
        }
        refuseWrite = false
        val priorWrites = writes
        record.origin.source = "x".repeat(256 * 1024)
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
        val record = CodeModeRecords.of("synthetic", 1).apply { origin.source = "x".repeat(2_000) }
        retaining.save(listOf(record), emptyList())
        assertTrue(escaped.contains(record.origin.source))
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
            CodeModeRecords.of("synthetic", index).apply { origin.source = "x".repeat(8_192) }
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
        records.last().progress.output = "completion"
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

    @Test
    fun `a source streamed larger after its save stays charged while the saved snapshot holds the old one`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
        val record = CodeModeRecords.of("synthetic", 1).apply { origin.source = "x".repeat(200_000) }
        val registry = registry(record, roomy)
        try {
            val saved = record.origin.source
            val streamed = saved + "y".repeat(100_000)
            registry.source.append(record, streamed)
            // The kept snapshot still holds the saved source and the record holds the streamed one: both are live.
            val charged = settledCharge(roomy)
            assertTrue(
                charged >= saved.length + streamed.length.toLong(),
                "$charged bytes charged for ${saved.length + streamed.length} live source characters",
            )
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `one appended character that re-widens a Latin-1 source is charged at the new width`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
        val record = CodeModeRecords.of("synthetic", 1).apply { origin.source = "x".repeat(200_000) }
        val registry = registry(record, roomy)
        try {
            val saved = record.origin.source
            val widened = saved + "\u0101"
            registry.source.append(record, widened)
            // The whole streamed source now stores two bytes a character, beside the saved one the snapshot holds.
            val charged = settledCharge(roomy)
            assertTrue(
                charged >= saved.length + 2L * widened.length,
                "$charged bytes charged for ${saved.length + 2L * widened.length} live source bytes",
            )
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `a result supplied again stays charged while the saved snapshot holds the one it replaces`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
        val saved = CodeModeResult("call-1", "r".repeat(200_000), false)
        val record = CodeModeRecords.of("synthetic", 1).apply { accepted.accept(mapOf("call-1" to saved), emptyMap()) }
        val registry = registry(record, roomy)
        try {
            val again = CodeModeResult("call-1", "s".repeat(200_000), false)
            registry.acceptResults(record, "digest-2", mapOf("call-1" to again))
            // The kept snapshot still holds the saved output and the record holds the new one: both are live.
            val charged = settledCharge(roomy)
            assertTrue(
                charged >= saved.output.length + again.output.length.toLong(),
                "$charged bytes charged for ${saved.output.length + again.output.length} live result characters",
            )
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `loss error is charged at stored width before a refused durable save`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record, roomy, writer)
        try {
            val before = checkNotNull(record.heapLease).bytes
            val message = "x".repeat(200_000) + "ā"
            val growth = CodeModeWeight.STORED.text(message) - CodeModeWeight.STORED.text("")
            val hold = checkNotNull(roomy.reserve(roomy.available.value - growth))
            try {
                capacityRefused { registry.lose(record, message) }
                assertEquals(message, record.error)
                assertEquals(CodeModePhase.LOST, record.phase)
                assertEquals(before + growth, checkNotNull(record.heapLease).bytes)
            } finally {
                hold.close()
            }
        } finally {
            refuseWrite = false
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `head stop error is charged before a refused durable save without recording a use`() {
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record, heap, writer)
        val cell = ClosingCell()
        assertTrue(registry.attach(record, cell))
        try {
            val before = checkNotNull(record.heapLease).bytes
            val lastUse = record.progress.updatedAt
            val message = "completed client call ids=[]; source was not rerun"
            val growth = CodeModeWeight.STORED.text(message) - CodeModeWeight.STORED.text("")
            val hold = checkNotNull(heap.reserve(heap.available.value - growth))
            try {
                capacityRefused { registry.onHeadStop() }
                assertEquals(message, record.error)
                assertEquals(CodeModePhase.LOST, record.phase)
                assertTrue(cell.closed)
                assertEquals(lastUse, record.progress.updatedAt)
                assertEquals(before + growth, checkNotNull(record.heapLease).bytes)
            } finally {
                hold.close()
            }
        } finally {
            refuseWrite = false
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `a replaced error remains charged on every retained saved snapshot`() {
        val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
        val saved = "r".repeat(200_000)
        val record = CodeModeRecords.of("synthetic", 1).apply { error = saved }
        val registry = registry(record, roomy)
        try {
            val first = record.heapSnapshots.mapNotNull { it.get() }
            assertTrue(first.isNotEmpty(), "the control must retain a real saved snapshot")
            record.progress.updatedAt++
            registry.save()
            val snapshots = record.heapSnapshots.mapNotNull { it.get() }.filter { it.error === saved }
            assertTrue(snapshots.size >= 2, "each separately retained snapshot must be admitted")
            java.lang.ref.Reference.reachabilityFence(first)
            val replacement = "s".repeat(200_000) + "ā"
            registry.lose(record, replacement)
            val charged = settledCharge(roomy)
            val minimum = CodeModeWeight.STORED.record(record) +
                snapshots.size * CodeModeWeight.STORED.text(saved)
            assertTrue(
                charged >= minimum,
                "$charged bytes must cover the live replacement and every saved error",
            )
            assertEquals(replacement, record.error)
            assertTrue(snapshots.all { it.error === saved })
            java.lang.ref.Reference.reachabilityFence(snapshots)
        } finally {
            refuseWrite = false
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `refused loss error growth still closes the cell and records a terminal loss`() {
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record)
        val cell = ClosingCell()
        assertTrue(registry.attach(record, cell))
        try {
            val before = checkNotNull(record.heapLease).bytes
            val hold = checkNotNull(heap.reserve(heap.available.value))
            try {
                capacityRefused { registry.lose(record, "lost".repeat(200_000)) }
                assertEquals(CodeModePhase.LOST, record.phase)
                assertEquals("", record.error, "a refused payload uses the already-counted empty error")
                assertTrue(record.terminal())
                assertTrue(cell.closed)
                assertEquals(null, registry.cell(record))
                assertEquals(before, checkNotNull(record.heapLease).bytes)
            } finally {
                hold.close()
            }
            registry.save()
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    @Test
    fun `refused head stop error growth still closes every cell without recording a use`() {
        val record = CodeModeRecords.of("synthetic", 1)
        val registry = registry(record)
        val cell = ClosingCell()
        assertTrue(registry.attach(record, cell))
        try {
            val before = checkNotNull(record.heapLease).bytes
            val lastUse = record.progress.updatedAt
            val hold = checkNotNull(heap.reserve(heap.available.value))
            try {
                capacityRefused { registry.onHeadStop() }
                assertEquals(CodeModePhase.LOST, record.phase)
                assertEquals("", record.error, "a refused payload uses the already-counted empty error")
                assertTrue(record.terminal())
                assertTrue(cell.closed)
                assertEquals(null, registry.cell(record))
                assertEquals(lastUse, record.progress.updatedAt)
                assertEquals(before, checkNotNull(record.heapLease).bytes)
            } finally {
                hold.close()
            }
            registry.save()
        } finally {
            registry.timed.finish { registry.onHeadStop() }
        }
    }

    /** The witness an issued step keeps: what the heap ledger charges for it, and what a save writes.
     *  @Nested so this file's own class stays under the size law while the controls live beside their rig. */
    @Nested
    inner class IssuedWitness {
        /** The echo witness a step keeps is prose as large as the step's own text. Uncharged, a conversation whose
         *  steps each kept one would hold megabytes the ledger never saw, and the 2 GiB journal of Oct 1 is what an
         *  unweighed retained payload costs. It is charged while the record holds it and while a snapshot does. */
        @Test
        fun `an issued step's delivered witness is charged while the record and its saved snapshot hold it`() {
            val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
            val witness = "w".repeat(200_000)
            val record = CodeModeRecords.of("synthetic", 1).apply {
                issued += CodeModeIssuedStep("digest-synthetic-1", emptyList(), witness)
            }
            val registry = registry(record, roomy)
            try {
                val charged = settledCharge(roomy)
                assertTrue(
                    charged >= witness.length,
                    "$charged bytes charged for ${witness.length} live witness characters",
                )
            } finally {
                registry.timed.finish { registry.onHeadStop() }
            }
        }

        /** The native items a step was sent (an encrypted reasoning item is as large as a model's thinking) are the
         *  same kind of retained payload as the prose. They are charged once: the record and its saved snapshot share
         *  one charge, so two separate charges would weigh them twice. */
        @Test
        fun `an issued step's delivered native items are charged once while the record and its snapshot hold them`() {
            val roomy = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
            val native = listOf(
                buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_synthetic")
                    put("summary", buildJsonArray { })
                    put("encrypted_content", "e".repeat(200_000))
                },
            )
            val weight = CodeModeWeight.STORED.json(JsonArray(native))
            val record = CodeModeRecords.of("synthetic", 1).apply {
                issued += CodeModeIssuedStep("digest-synthetic-1", emptyList(), deliveredNative = native)
            }
            val registry = registry(record, roomy)
            try {
                val charged = settledCharge(roomy)
                assertTrue(charged >= weight, "$charged bytes charged for $weight bytes of live native items")
                assertTrue(charged < 2 * weight, "$charged bytes charged twice for $weight bytes of native items")
            } finally {
                registry.timed.finish { registry.onHeadStop() }
            }
        }

        /** A step with no witness is a legacy step and a step whose cell delivered neither prose nor native items. Its
         *  saved bytes are the bytes the daemon wrote before the fields existed, so an old checkpoint still replays and
         *  a journal written by this daemon is readable by the one before it. */
        @Test
        fun `a step with no delivered witness saves the bytes it always did`() {
            val record = CodeModeRecords.of("synthetic", 1).apply {
                issued += CodeModeIssuedStep("digest-synthetic-1", emptyList())
            }
            val registry = registry(record)
            try {
                val saved = CodeModeStateFiles(location.dir).records().single()
                val step = saved.getValue("issued").jsonArray.single().jsonObject
                assertFalse("deliveredText" in step, "a null witness is not a key: $step")
                assertFalse("deliveredNative" in step, "null native items are not a key: $step")
                assertEquals(setOf("requestDigest", "calls"), step.keys, "the step's saved keys: $step")
            } finally {
                registry.timed.finish { registry.onHeadStop() }
            }
        }
    }

    @Nested
    inner class TerminalErrorOwnership {
        @Test
        fun `loss keeps cancellation primary when the charged error cannot be forced`() {
            val record = CodeModeRecords.of("synthetic", 1)
            val registry = registry(record, heap, writer)
            val cell = ClosingCell()
            assertTrue(registry.attach(record, cell))
            try {
                refuseWrite = true
                val cancellation = CancellationException("synthetic cancellation")
                registry.lose(record, "source was not rerun", cancellation)
                assertEquals(CodeModePhase.LOST, record.phase)
                assertTrue(cell.closed)
                assertTrue(checkNotNull(record.heapLease).bytes >= CodeModeWeight.STORED.record(record))
                assertTrue(cancellation.suppressed.single() is CodeModePersistenceException)
            } finally {
                refuseWrite = false
                registry.timed.finish { registry.onHeadStop() }
            }
        }

        @Test
        fun `reusing the same error object needs no additional snapshot charge`() {
            val saved = "r".repeat(2_000)
            val record = CodeModeRecords.of("synthetic", 1).apply { error = saved }
            val registry = registry(record)
            try {
                val snapshots = record.heapSnapshots.mapNotNull { it.get() }
                assertTrue(snapshots.any { it.error === saved })
                val hold = checkNotNull(heap.reserve(heap.available.value))
                try {
                    registry.lose(record, saved, CancellationException("synthetic cancellation"))
                    assertTrue(record.error === saved, "unchanged payload must not be refused at capacity")
                    assertEquals(CodeModePhase.LOST, record.phase)
                } finally {
                    hold.close()
                }
                java.lang.ref.Reference.reachabilityFence(snapshots)
            } finally {
                registry.timed.finish { registry.onHeadStop() }
            }
        }

        @Test
        fun `refused retained snapshot charge preserves the counted old error and terminal fallback`() {
            val saved = "r".repeat(2_000)
            val record = CodeModeRecords.of("synthetic", 1).apply { error = saved }
            val registry = registry(record)
            try {
                val snapshots = record.heapSnapshots.mapNotNull { it.get() }
                assertTrue(snapshots.any { it.error === saved })
                val before = checkNotNull(record.heapLease).bytes
                val hold = checkNotNull(heap.reserve(heap.available.value))
                try {
                    registry.lose(record, "short", CancellationException("synthetic cancellation"))
                    assertEquals("", record.error, "zero record growth still admits retained snapshot ownership")
                    assertEquals(CodeModePhase.LOST, record.phase)
                    assertEquals(before, checkNotNull(record.heapLease).bytes)
                    assertTrue(snapshots.all { it.error === saved })
                } finally {
                    hold.close()
                }
                java.lang.ref.Reference.reachabilityFence(snapshots)
            } finally {
                registry.timed.finish { registry.onHeadStop() }
            }
        }
    }

    private class ClosingCell : CodeModeCell {
        var closed = false

        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
            error("this accounting control never executes a source")

        override fun close() {
            closed = true
        }
    }

    /** The budget's charge once a full collection refunds nothing more: a save's stages are gone, owners remain. */
    private fun settledCharge(budget: HeapBudget): Long = runBlocking {
        repeat(40) {
            val seen = budget.available.value
            System.gc()
            withTimeoutOrNull(300.milliseconds) { budget.available.first { it != seen } }
                ?: return@runBlocking budget.limitBytes - seen
        }
        error("the ledger never settled")
    }

    private fun registry(
        record: CodeModeRecord,
        budget: HeapReservations = heap,
        writer: CodeModeStateWrite? = null,
    ): CodexCodeModeRegistry {
        val config = CodeModeBridgeConfig(
            runtimes = { error("this reservation control must not start a worker") },
            state = location,
            clock = Clock.fixed(Instant.ofEpochMilli(record.progress.updatedAt), ZoneOffset.UTC),
        )
        return CodexCodeModeRegistry(config, Json, 1.days, writer = writer, heap = budget).also {
            assertTrue(it.add(record))
        }
    }
}
