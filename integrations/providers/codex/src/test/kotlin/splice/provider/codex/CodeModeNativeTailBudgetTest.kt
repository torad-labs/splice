package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.turn.GatewayCustomCall
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.stream.source.CodeModeSourcePayloads
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

// why: exercise ownership transfer against the same 64 MiB ledger ceiling, without changing production limits.
private const val TAIL_HEAP_BYTES = 64L * 1024 * 1024

// why: metadata can still grow, while there is deliberately no room for a second copy of the 192 KiB payload.
private const val TAIL_TRANSFER_HEADROOM = 8L * 1024

class CodeModeNativeTailBudgetTest(@param:TempDir private val dir: Path) {
    private val body = "b".repeat(128 * 1024)
    private val cipher = "r".repeat(64 * 1024)

    @Test
    fun `a pending raw tail transfers to record continuity at capacity without paying for its payload again`() {
        TailBudgetState(dir).use { state ->
            val tail = tail()
            assertTrue(state.payloads.retain(state.record, tail))
            checkNotNull(state.heap.reserve(state.heap.available.value - TAIL_TRANSFER_HEADROOM)).use {
                assertDoesNotThrow {
                    assertTrue(state.registry.source.updateContinuity(state.record, state.call, continuity(tail)))
                }
                assertEquals(tail, state.record.carry.nativeAfterExec)
                assertTrue(state.heap.available.value >= 0, "a transfer cannot exceed the existing ledger")
            }
            assertEquals(tail, state.persistedTail(), "the transferred raw ids and payload survive the durable journal")
        }
    }

    @Test
    fun `undelivered native tail payload stays charged before it enters durable continuity`() {
        TailBudgetState(dir).use { state ->
            val available = state.heap.available.value
            assertTrue(state.payloads.retain(state.record, tail()))
            val minimum = body.toByteArray().size + cipher.toByteArray().size
            assertTrue(available - state.heap.available.value >= minimum, "pending raw payload must own its bytes")
            assertEquals(emptyList<JsonElement>(), state.record.carry.nativeAfterExec)
        }
    }

    @Test
    fun `a pending tail that cannot fit is refused without claiming captured history`() {
        TailBudgetState(dir).use { state ->
            checkNotNull(state.heap.reserve(state.heap.available.value - TAIL_TRANSFER_HEADROOM)).use {
                assertThrows<HeapCapacityException> { state.payloads.retain(state.record, tail()) }
                assertEquals(emptyList<JsonElement>(), state.record.carry.nativeAfterExec)
                assertTrue(state.heap.available.value >= 0, "pending receipts cannot bypass the 64 MiB ceiling")
            }
        }
    }

    private fun continuity(tail: List<JsonObject>) = CodeModeContinuity(emptyList(), emptyList(), tail)

    private fun tail(): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "reasoning")
            put("id", "retained-r2")
            put("status", "completed")
            put("encrypted_content", cipher)
            put("summary", JsonArray(emptyList()))
        },
        buildJsonObject {
            put("type", "message")
            put("id", "retained-b")
            put("status", "completed")
            put("role", "assistant")
            put("phase", "commentary")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "output_text"); put("text", body) })))
        },
    )
}

/** Pause disk saves after the durable seal so the capacity control measures resident ownership, not encoding peaks. */
private class TailBudgetState(dir: Path) : AutoCloseable {
    val heap = HeapBudget(Long.MAX_VALUE, TAIL_HEAP_BYTES)
    private var journaling = true
    private val location = CodeModeStateLocation(
        dir.resolve("state"),
        dir.resolve("legacy.json"),
        journaling = CodeModeJournaling { journaling },
    )
    val record = CodeModeRecords.of("tail-budget", 0, 0).also { it.origin.source = "" }
    private val program = "return 'done';"
    val call = GatewayCustomCall(
        record.origin.outerCallId,
        CODE_MODE_TOOL_NAME,
        program,
        buildJsonObject {
            put("type", "custom_tool_call")
            put("id", "budget-exec")
            put("call_id", record.origin.outerCallId)
            put("name", CODE_MODE_TOOL_NAME)
            put("status", "completed")
            put("input", program)
        },
    )
    val registry = CodexCodeModeRegistry(
        CodeModeBridgeConfig(
            { error("record ownership tests do not run a worker") },
            location,
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        ),
        Json { encodeDefaults = true },
        1.hours,
        heap = heap,
    )
    val payloads = CodeModeSourcePayloads(registry)

    init {
        check(registry.add(record))
        check(registry.source.seal(record, call, CodeModeContinuity(emptyList(), emptyList(), emptyList())))
        journaling = false
    }

    fun persistedTail(): List<JsonElement>? {
        journaling = true
        registry.save()
        val file = Files.list(location.dir).use { it.toList().single() }
        return CodeModeStateJournal.read(file, Json).records.single().nativeAfterExec
    }

    override fun close() {
        registry.timed.finish { registry.onHeadStop() }
    }
}
