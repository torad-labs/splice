package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamLedger
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import java.nio.file.Files

class CodeModeHistoryScaleTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `two thousand seeded appends retain exact native branches with linear durable growth`() {
        val writingHeap = HeapBudget(Long.MAX_VALUE, SCALE_HEAP_BYTES)
        val storage = CodeModeHistoryStorage(stateLocation(), json, SCALE_DISK_BYTES, writingHeap)
        val ledger = CodeModeUpstreamLedger(storage = storage, cacheBytes = SCALE_HEAP_BYTES)
        val first = message("user", "p".repeat(PREFIX_CHARACTERS))
        var client: List<JsonElement> = listOf(first)
        var model: List<JsonElement> = listOf(first, reasoning("initial"))
        repeat(APPENDS) { seed ->
            client = client + message("user", "continue-$seed")
            model = model + reasoning("seed-$seed") + message("assistant", "answer-${seed * SEED_MULTIPLIER}")
            ledger.record("long", CodeModeLedgerEntry(client, model, emptyList()))
        }
        val file = Files.list(stateLocation().dir.resolve("history")).use { files ->
            files.filter { it.fileName.toString().endsWith(".jsonl") }.findFirst().orElseThrow()
        }
        val bound = PREFIX_CHARACTERS * 2L + APPENDS * JOURNAL_BYTES_PER_APPEND
        assertTrue(Files.size(file) < bound, "history journal grew beyond shared prefix + bounded append suffixes")
        val readingHeap = HeapBudget(Long.MAX_VALUE, SCALE_HEAP_BYTES)
        val restored = CodeModeUpstreamLedger(
            storage = CodeModeHistoryStorage(stateLocation(), json, SCALE_DISK_BYTES, readingHeap),
            cacheBytes = SCALE_HEAP_BYTES,
        ).entries("long")
        assertEquals(APPENDS, restored.size)
        assertEquals(model, restored.first().sent)
        listOf(0, 7, 31, 511, APPENDS - 1).forEach { age ->
            val steps = APPENDS - age
            assertEquals(model.take(INITIAL_MODEL_ITEMS + steps * MODEL_ITEMS_PER_APPEND), restored[age].sent)
            assertEquals(client.take(steps + 1), restored[age].covered)
        }
        assertTrue(readingHeap.available.value > 0L, "a 64 MiB ledger could not retain the shared native graph")
    }

    @Test
    fun `interleaved branches reuse their addressed parent and never rewrite another branch`() {
        val ledger = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(stateLocation(), json, SCALE_DISK_BYTES))
        val root = listOf(message("user", "root"))
        val branches = List(BRANCHES) { branch -> root + message("user", "branch-$branch") }
        val heads = mutableMapOf<Int, Long>()
        val expected = branches.toMutableList()
        repeat(BRANCH_APPENDS) { step ->
            val branch = step % BRANCHES
            expected[branch] = expected[branch] + reasoning("branch-$branch-step-$step")
            ledger.record(
                "branches",
                CodeModeLedgerEntry(branches[branch], expected[branch], emptyList()),
                heads[branch],
            )
            heads[branch] = checkNotNull(ledger.newest("branches"))
        }
        val restored = CodeModeUpstreamLedger(
            storage = CodeModeHistoryStorage(stateLocation(), json, SCALE_DISK_BYTES),
        ).entries("branches")
        heads.forEach { (branch, id) -> assertEquals(expected[branch], restored.single { it.id == id }.sent) }
    }

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun reasoning(id: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "opaque-$id")
        put("summary", kotlinx.serialization.json.JsonArray(emptyList()))
    }
}

private const val PREFIX_CHARACTERS = 50_000
private const val APPENDS = 2_000
private const val SCALE_HEAP_BYTES = 64L * 1024 * 1024
private const val SCALE_DISK_BYTES = 32L * 1024 * 1024
private const val JOURNAL_BYTES_PER_APPEND = 2_048L
private const val INITIAL_MODEL_ITEMS = 2
private const val MODEL_ITEMS_PER_APPEND = 2
private const val SEED_MULTIPLIER = 17
private const val BRANCHES = 7
private const val BRANCH_APPENDS = 280
