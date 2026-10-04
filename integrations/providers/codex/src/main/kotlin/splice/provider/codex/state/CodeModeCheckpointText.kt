// NEW: the entire legacy pretty checkpoint reserves its decoding peak before text growth.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapWeights
import splice.provider.codex.CodeModePersistedState

// why: checkpoint text, its copied parse input, JSON nodes and decoded snapshot containers coexist.
private const val CHECKPOINT_TEXT_FACTOR = 16L

// why: builder and decoder metadata exist before the first checkpoint character.
private const val CHECKPOINT_METADATA_BYTES = 256L

/** Accumulates only the initial checkpoint. Journal deltas never enter this growing text. */
internal class CodeModeCheckpointText(private val heap: HeapBudget) : AutoCloseable {
    private val peak = heap.reserve(CHECKPOINT_METADATA_BYTES * CHECKPOINT_TEXT_FACTOR)
        ?: throw HeapCapacityException()
    private val text = StringBuilder(0)
    private var lastStart = 0
    private var lines = 0

    fun append(line: String) {
        val needed = text.length.toLong() + line.length + if (lines > 0) 1 else 0
        if (needed >= Int.MAX_VALUE) throw HeapCapacityException()
        val capacity = if (needed <= text.capacity()) {
            text.capacity().toLong()
        } else {
            maxOf(needed, text.capacity() * 2L + 2L)
        }
        val weight = HeapWeights.multiply(capacity + CHECKPOINT_METADATA_BYTES, CHECKPOINT_TEXT_FACTOR)
        if (!peak.resize(weight)) throw HeapCapacityException()
        lastStart = text.length
        if (lines++ > 0) text.append('\n')
        text.append(line)
    }

    fun checkpoint(json: Json): JsonObject {
        require(text.isNotBlank()) { "code-mode journal has no checkpoint" }
        return json.parseToJsonElement(text.toString()).jsonObject
    }

    fun finish(json: Json, committed: Boolean): CodeModePersistedState {
        val state = try {
            json.decodeFromString<CodeModePersistedState>(text.toString())
        } catch (whole: IllegalArgumentException) {
            if (committed) throw whole
            json.decodeFromString<CodeModePersistedState>(text.substring(0, lastStart))
        }
        return CodeModeHeap.ownState(state, heap)
    }

    override fun close() {
        peak.close()
    }
}
