// NEW: a turn's perf row waits for the source round it left streaming, so that round's tokens land on it.
package splice.head.perf

import splice.core.turn.Usage
import splice.upstream.PostingTurnRow
import splice.upstream.RowRelease
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The perf row of one turn, which can return while a source round it posted still streams: code mode parks its
 * script on the first tool call, and the round that writes the script reports its usage only at its terminal. The
 * row waits for every round held on it and then carries them, so no later turn's row bills them. It holds a row
 * and nothing else: the client's message, the socket and the admission slot never wait on it.
 */
internal class SourceRowHold : PostingTurnRow {
    /** Writes the row, with the turn's whole usage when a held round settled one. Once per row, after the last
     *  [RowRelease] of its holds, never once per release. */
    fun interface RowWrite {
        fun write(usage: Usage?)
    }

    private val lock = Any()
    private var open = 0
    private var settled: Usage? = null
    private var pending: RowWrite? = null

    override fun hold(): RowRelease {
        synchronized(lock) { open += 1 }
        val once = AtomicBoolean(false)
        return RowRelease { usage -> if (once.compareAndSet(false, true)) released(usage) }
    }

    /** Writes [row] now, or once the last held round settles. */
    fun writeOrHold(row: RowWrite) {
        val usage = synchronized(lock) {
            if (open > 0) {
                pending = row
                return
            }
            settled
        }
        row.write(usage)
    }

    /** A head stop writes a waiting row with what is known. A round that settles after it changes nothing. */
    fun flush() {
        val (row, usage) = synchronized(lock) {
            val row = pending ?: return
            pending = null
            open = 0
            row to settled
        }
        row.write(usage)
    }

    private fun released(usage: Usage?) {
        val (row, carried) = synchronized(lock) {
            if (usage != null) settled = usage
            if (open == 0) return
            open -= 1
            if (open > 0) return
            val row = pending ?: return
            pending = null
            row to settled
        }
        row.write(carried)
    }
}

/** One head's rows still waiting on source rounds, so a head stop writes them with what is known ([flush]). A hard
 *  kill loses them: they live in memory until their rounds settle. */
internal class HeldRows {
    private val waiting: MutableSet<SourceRowHold> = ConcurrentHashMap.newKeySet()

    /** Writes [row] now, or once every round [hold] waits on has settled. */
    fun write(hold: SourceRowHold, row: SourceRowHold.RowWrite) {
        waiting += hold
        hold.writeOrHold { usage ->
            waiting -= hold
            row.write(usage)
        }
    }

    /** Every waiting row, written now; a round that settles afterwards changes nothing. */
    fun flush() {
        waiting.toList().forEach(SourceRowHold::flush)
    }
}
