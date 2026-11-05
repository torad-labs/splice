package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.memory.HeapBudget
import splice.core.memory.HeapLease
import splice.provider.codex.state.CodeModeStateDirectory
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.save.CodeModeStateWrite
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class CodeModeCacheFailureIsolationTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }
    private val clock = MutableClock(1_790_000_000_000L)
    private val heap = HeapBudget(Long.MAX_VALUE, 64 * 1024 * 1024)
    private var writeFailure: Exception? = null
    private val writer = CodeModeStateWrite { file, text ->
        if (file == path("older")) writeFailure?.let { throw it }
        CodeModeStateJournal.write(file, text)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a failed older candidate stays intact while a newer candidate cools`(capacityRefusal: Boolean) {
        val registry = registry()
        val before = Files.readAllBytes(path("older"))
        val pressure = if (capacityRefusal) reservePressure() else null
        if (!capacityRefusal) writeFailure = IOException("refused journal write")
        try {
            registry.cache.trim()

            assertEquals(listOf("older"), registry.records.map(CodeModeRecord::key), "the newer key was not cooled")
            assertEquals("latest older", registry.records.single().progress.output)
            assertArrayEquals(before, Files.readAllBytes(path("older")), "the failed candidate's journal changed")
            assertEquals("latest newer", CodeModeStateJournal.read(path("newer"), json).records.single().output)
        } finally {
            pressure?.close()
            writeFailure = null
        }

        registry.cache.trim()
        assertTrue(registry.records.isEmpty(), "the previously failed candidate must remain eligible for retry")
        assertEquals("latest older", registry.completed("older").single().progress.output)
        assertEquals("latest newer", registry.completed("newer").single().progress.output)
    }

    @Test
    fun `cancellation stops trimming and leaves both candidates available for retry`() {
        val registry = registry()
        val before = Files.readAllBytes(path("older"))
        val cancellation = CancellationException("cancel cooling")
        writeFailure = cancellation
        try {
            assertSame(cancellation, assertThrows(CancellationException::class.java) { registry.cache.trim() })
            assertEquals(setOf("older", "newer"), registry.records.map(CodeModeRecord::key).toSet())
            assertArrayEquals(before, Files.readAllBytes(path("older")))
        } finally {
            writeFailure = null
        }
        registry.cache.trim()
        assertTrue(registry.records.isEmpty(), "cancellation must release ownership for a later trim")
        assertEquals("latest older", registry.completed("older").single().progress.output)
        assertEquals("latest newer", registry.completed("newer").single().progress.output)
    }

    private fun registry(): CodexCodeModeRegistry {
        val records = listOf("older", "newer").mapIndexed { index, key ->
            CodeModeRecords.of(key, 0, clock.millis() + index * 100).also {
                it.phase = CodeModePhase.COMPLETED
                it.progress.output = "saved $key"
                if (key == "older") it.origin.source = "x".repeat(256 * 1024)
            }
        }
        CodexCodeModeStore(stateLocation(), json, {}, heap = heap).save(records, emptyList())
        val config = CodeModeBridgeConfig(
            { error("a cache retry must not execute a script") },
            stateLocation(),
            retention = CodeModeRetention(bytes = 1),
            clock = clock,
            log = { logLines += it },
        )
        val registry = CodexCodeModeRegistry(config, json, 1.hours, writer = writer, heap = heap)
        listOf("older", "newer").forEach { key ->
            registry.recordsFor(key).single().also {
                it.progress.updatedAt++
                it.progress.output = "latest $key"
            }
        }
        return registry
    }

    private fun path(key: String) = CodeModeStateDirectory(stateLocation().dir, json, {}).path(key)

    /** Settle discarded boot/read owners before leaving enough capacity only for the small candidate. */
    private fun reservePressure(): HeapLease = runBlocking {
        repeat(40) {
            val available = heap.available.value
            System.gc()
            if (withTimeoutOrNull(300.milliseconds) { heap.available.first { it != available } } == null) {
                return@runBlocking checkNotNull(heap.reserve(heap.available.value - 256 * 1024))
            }
        }
        error("the heap ledger never settled")
    }
}
