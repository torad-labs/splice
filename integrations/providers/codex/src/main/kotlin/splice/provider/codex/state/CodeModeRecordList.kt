package splice.provider.codex.state

import splice.provider.codex.CodeModeRecord
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

    init {
        ordered.forEach(::retain)
    }

    override val size: Int get() = ordered.size

    override fun get(index: Int): CodeModeRecord = ordered[index]

    override fun contains(element: CodeModeRecord): Boolean =
        owners.containsKey(element) || ordered.contains(element)

    override fun add(index: Int, element: CodeModeRecord) {
        ordered.add(index, element)
        retain(element)
        modCount++
    }

    override fun set(index: Int, element: CodeModeRecord): CodeModeRecord {
        val previous = ordered.set(index, element)
        release(previous)
        retain(element)
        return previous
    }

    override fun removeAt(index: Int): CodeModeRecord {
        val previous = ordered.removeAt(index)
        release(previous)
        modCount++
        return previous
    }

    override fun clear() {
        if (ordered.isEmpty()) return
        ordered.clear()
        owners.clear()
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
