package splice.upstream.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.core.memory.HeapCategory
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwnerState
import splice.core.wire.MessageFragmentInputs

private data class MessageFragmentEntry(
    val input: MessageFragmentInput,
    val output: JsonElement,
    val charge: MessageFragmentCharge,
)

/** The bounds include evicted fragments still owned by readers, across every builder/head/dialect. */
internal object MessageFragmentStore {
    private val lock = Any()
    private val heap = JvmHeap.budget.readShare()
    private val entries = LinkedHashMap<FragmentKey, MessageFragmentEntry>(
        FRAGMENT_MAX_ENTRIES,
        FRAGMENT_ACCESS_LOAD_FACTOR,
        true,
    )
    private var retainedBytes = 0L
    private var retainedEntries = 0

    fun find(key: FragmentKey, input: MessageFragmentInput): JsonElement? {
        val candidate = synchronized(lock) { entries[key]?.let { it to it.charge.reader() } } ?: return null
        val (prior, reader) = candidate
        // Even an empty fragment keeps its private input admitted throughout out-of-lock equality.
        return reader.use { prior.output.takeIf { input.same(prior.input) } }
    }

    fun save(key: FragmentKey, input: MessageFragmentInput, output: JsonElement): JsonElement {
        val bytes = HeapJson.add(HeapJson.text(key.context), HeapJson.add(input.bytes, HeapJson.bytes(output)))
        val weight = HeapJson.add(FRAGMENT_OWNER_BYTES, bytes)
        if (weight > FRAGMENT_MAX_ENTRY_BYTES) return output
        val charge = synchronized(lock) { admitted(key, weight) } ?: return output
        var published = false
        try {
            val owned = MessageFragmentInputs.freeze(output)
            val source = input.freeze()
            // Array callers append its elements, not its wrapper. Pin the roots they actually retain.
            if (owned is JsonArray) owned.forEach(charge::pin) else charge.pin(owned)
            synchronized(lock) {
                entries.put(key, MessageFragmentEntry(source, owned, charge))?.charge?.evict()
            }
            published = true
            return owned
        } finally {
            if (!published) charge.evict()
        }
    }

    private fun admitted(key: FragmentKey, weight: Long): MessageFragmentCharge? {
        entries.remove(key)?.charge?.evict()
        evictUntilFits(weight)
        if (!fits(weight)) return null
        val lease = heap.reserve(weight, HeapCategory.RECENT_HISTORY, HeapOwnerState.ACTIVE) ?: return null
        retainedBytes += weight
        retainedEntries++
        return MessageFragmentCharge(lease) {
            synchronized(lock) {
                retainedBytes -= weight
                retainedEntries--
            }
        }
    }

    private fun fits(weight: Long): Boolean =
        retainedBytes + weight <= FRAGMENT_MAX_BYTES && retainedEntries < FRAGMENT_MAX_ENTRIES

    private fun evictUntilFits(weight: Long) {
        while (entries.isNotEmpty() && !fits(weight)) {
            val eldest = entries.entries.iterator()
            val removed = eldest.next().value
            eldest.remove()
            removed.charge.evict()
        }
        // Reader-owned capacity may remain after eviction. Fall back without waiting or losing functionality.
    }
}

private const val FRAGMENT_MAX_BYTES = 8 * 1024 * 1024L
private const val FRAGMENT_MAX_ENTRIES = 2048
private const val FRAGMENT_MAX_ENTRY_BYTES = 512 * 1024L
private const val FRAGMENT_OWNER_BYTES = 256L
private const val FRAGMENT_ACCESS_LOAD_FACTOR = 0.75f
