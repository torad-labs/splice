package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.InputDigest
import splice.core.reasoning.ReasoningHistory
import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonScalars

/** Sparse terminal observations cannot manufacture an ordering over the missing observed items. */
class ReasoningSparseTerminalOrderTest {
    private val indexes = ReasoningPositionIndex()
    private val position = ReasoningHistory().apply { text("user", "Start") }.key

    @Test
    fun `generated terminal subsets and permutations preserve observed order unless complete`() {
        val ids = listOf("rs_A", "rs_B", "rs_C")
        for (observed in permutations(ids)) {
            for (terminal in subsets(ids).flatMap(::permutations)) {
                val cache = ReasoningCache()
                retain(cache, observed, terminal = false)
                retain(cache, terminal, terminal = true)
                val expected = if (terminal.toSet() == observed.toSet()) terminal else observed
                assertEquals(expected, replayed(cache), "observed=$observed terminal=$terminal")
            }
        }
    }

    @Test
    fun `terminal only complete order and independent rounds remain usable`() {
        val cache = ReasoningCache()
        retain(cache, listOf("rs_C", "rs_A", "rs_B"), terminal = true)
        retain(cache, listOf("rs_D", "rs_E"), terminal = false, round = "second-round")
        retain(cache, listOf("rs_E"), terminal = true, round = "second-round")
        assertEquals(listOf("rs_C", "rs_A", "rs_B", "rs_D", "rs_E"), replayed(cache))
    }

    @Test
    fun `different sparse terminal batches cannot collectively invent a complete order`() {
        val cache = ReasoningCache()
        retain(cache, listOf("rs_A", "rs_B", "rs_C"), terminal = false)
        retain(cache, listOf("rs_C", "rs_A"), terminal = true)
        retain(cache, listOf("rs_B"), terminal = true)
        assertEquals(listOf("rs_A", "rs_B", "rs_C"), replayed(cache))
        retain(cache, listOf("rs_C", "rs_B", "rs_A"), terminal = true)
        assertEquals(listOf("rs_C", "rs_B", "rs_A"), replayed(cache))
        retain(cache, listOf("rs_B"), terminal = true)
        assertEquals(listOf("rs_C", "rs_B", "rs_A"), replayed(cache))
    }

    private fun retain(cache: ReasoningCache, ids: List<String>, terminal: Boolean, round: String = "first-round") {
        val batch = if (terminal) InputDigest.hex(JsonArray(ids.map(::JsonPrimitive))) else null
        ids.forEachIndexed { index, id ->
            val envelope = checkNotNull(
                ReasoningReplay.encodeReasoningEnvelope(
                    buildJsonObject {
                        put("type", "reasoning")
                        put("id", id)
                        put("encrypted_content", "fixture-$id")
                    },
                ),
            )
            cache.put(
                "fixture-conversation",
                listOf(
                    indexes.index(
                        position,
                        envelope,
                        ReasoningItemOrder(round, index, terminal, id, terminalBatch = batch),
                    ),
                ),
                listOf(envelope),
            )
        }
    }

    private fun replayed(cache: ReasoningCache): List<String> =
        indexes.positions(cache.snapshot("fixture-conversation")).getValue(position).map { envelope ->
            val item = checkNotNull(ReasoningReplay.decodeReasoningEnvelope(envelope))
            checkNotNull(JsonScalars.str(item, "id"))
        }

    private fun subsets(ids: List<String>): List<List<String>> = (0 until (1 shl ids.size)).map { mask ->
        ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    }

    private fun permutations(ids: List<String>): List<List<String>> = if (ids.isEmpty()) {
        listOf(emptyList())
    } else {
        ids.flatMap { first -> permutations(ids - first).map { listOf(first) + it } }
    }
}
