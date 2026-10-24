package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.perf.InputDigest
import splice.provider.codex.history.CodeModeCallbackReceipt
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamLedger
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import java.nio.file.Files

class CodeModeHistoryRemovalBudgetTest : CodeModeBridgeTestSupport() {
    @Test
    fun `compaction reserves removed callback identity before encoding and preserves committed authority`() {
        val heap = HeapBudget(Long.MAX_VALUE, HEAP_BYTES)
        val json = Json { encodeDefaults = true }
        val location = stateLocation()
        val ledger = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(location, json, DISK_BYTES, heap))
        val id = "callback-" + "x".repeat(CALLBACK_CHARACTERS)
        val call = buildJsonObject {
            put("type", "function_call"); put("name", "t"); put("call_id", id); put("arguments", "{}")
        }
        val result = buildJsonObject {
            put("type", "function_call_output"); put("call_id", id); put("output", "ok")
        }
        val native = listOf(JsonPrimitive("native"))
        ledger.record(
            "k",
            CodeModeLedgerEntry(
                listOf(call, result),
                native,
                emptyList(),
                callbacks = mapOf(
                    id to CodeModeCallbackReceipt(InputDigest.hex(call), InputDigest.hex(result), emptyList(), false),
                ),
            ),
        )
        val file = location.dir.resolve("history").resolve(InputDigest.hex("k") + ".jsonl")
        val before = Files.readAllBytes(file)
        checkNotNull(heap.reserve(heap.available.value - AVAILABLE_BYTES)).use {
            assertThrows(HeapCapacityException::class.java) {
                ledger.record("k", CodeModeLedgerEntry(listOf(JsonPrimitive("compact")), native, emptyList()))
            }
            assertEquals(before.toList(), Files.readAllBytes(file).toList())
            assertEquals(native, ledger.entries("k").single().sent)
        }
        val restored = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(location, json, DISK_BYTES))
        assertEquals(native, restored.entries("k").single().sent)
    }
}

private const val CALLBACK_CHARACTERS = 1_000_000
private const val AVAILABLE_BYTES = 64_000L
private const val HEAP_BYTES = 128L * 1024 * 1024
private const val DISK_BYTES = 16L * 1024 * 1024
