package splice.provider.codex.state.collection

import splice.provider.codex.CodeModeRecord
import java.util.Collections
import java.util.IdentityHashMap
import java.util.RandomAccess

/** Ordered registry records with constant-time membership for their live owners.
 *  Streaming source commits ask this on every prefix under the head-wide lock. Identity counts
 *  track every mutation, including list compaction's temporary duplicates; a detached equal value
 *  retains ordinary List equality semantics. The index never outlives the list's ownership. */
internal class CodeModeRecordList(records: Collection<CodeModeRecord>) :
    AbstractMutableList<CodeModeRecord>(), RandomAccess {
    private val ordered = records.toMutableList()
    private val owners = IdentityHashMap<CodeModeRecord, Int>()

    // why: every query, sweep and turn start reads one conversation's records, and a busy head holds thousands
    // across all of them. Filtering the whole list per call was 38% of the daemon's CPU on Oct 15. The grouping
    // is rebuilt once after a membership change, under the same head-wide lock every reader of this list holds.
    private var byKey: Map<String, List<CodeModeRecord>>? = null

    init {
        ordered.forEach(::retain)
    }

    override val size: Int get() = ordered.size

    override fun get(index: Int): CodeModeRecord = ordered[index]

    override fun contains(element: CodeModeRecord): Boolean =
        owners.containsKey(element) || ordered.contains(element)

    /** Conversation [key]'s records in list order, as `filter { it.key == key }` reads them. A record's key never
     *  changes, so only adding, replacing or removing a record moves this view. */
    fun ofKey(key: String): List<CodeModeRecord> {
        val index = byKey ?: ordered.groupBy(CodeModeRecord::key)
            .mapValues { (_, kept) -> Collections.unmodifiableList(kept) }
            .also { byKey = it }
        return index[key].orEmpty()
    }

    override fun add(index: Int, element: CodeModeRecord) {
        ordered.add(index, element)
        retain(element)
        byKey = null
        modCount++
    }

    override fun set(index: Int, element: CodeModeRecord): CodeModeRecord {
        val previous = ordered.set(index, element)
        release(previous)
        retain(element)
        byKey = null
        return previous
    }

    override fun removeAt(index: Int): CodeModeRecord {
        val previous = ordered.removeAt(index)
        release(previous)
        byKey = null
        modCount++
        return previous
    }

    override fun clear() {
        if (ordered.isEmpty()) return
        ordered.clear()
        owners.clear()
        byKey = null
        modCount++
    }

    override fun removeAll(elements: Collection<CodeModeRecord>): Boolean =
        reindex(ordered.removeAll(elements))

    override fun retainAll(elements: Collection<CodeModeRecord>): Boolean =
        reindex(ordered.retainAll(elements))

    private fun reindex(changed: Boolean): Boolean {
        if (changed) {
            owners.clear()
            ordered.forEach(::retain)
            byKey = null
            modCount++
        }
        return changed
    }

    private fun retain(record: CodeModeRecord) {
        owners[record] = (owners[record] ?: 0) + 1
    }

    private fun release(record: CodeModeRecord) {
        val count = checkNotNull(owners[record])
        if (count == 1) owners.remove(record) else owners[record] = count - 1
    }
}

/** One conversation's records, in list order, from any record list the registry hands its parts. The registry's
 *  own list answers from its index; a working copy, such as an admission's candidate list, is filtered. */
internal object CodeModeRecordKeys {
    fun of(records: List<CodeModeRecord>, key: String): List<CodeModeRecord> =
        (records as? CodeModeRecordList)?.ofKey(key) ?: records.filter { it.key == key }
}
