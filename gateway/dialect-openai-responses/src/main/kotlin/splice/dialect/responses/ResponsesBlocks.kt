// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: BlockState is per-block
// wire-cursor state, stored/looked up by wire index only, never compared by value; ToolSalvage
// tracks tool blocks still OPEN at a mid-stream tear for re-anchor eligibility.
package splice.dialect.responses

import splice.core.index.WireBlockIndex

// Mutable per-block cursor. A data class here documents it as pure per-block state; it is only
// ever stored/looked up by wire index, never compared by value.
internal data class BlockState(
    val index: WireBlockIndex,
    var sawDelta: Boolean,
    // CX-01: accumulated tool-argument text for a function_call block, validated as JSON at close.
    // Empty for text/reasoning blocks (they never reach onArgs). Capped by BufferCapacity (NF-06).
    val args: StringBuilder = StringBuilder(),
)

/** Tool-block salvage ledger for mid-stream re-anchoring: tracks blocks still OPEN at a tear.
 *  A sweep-close of an open tool block committed PARTIAL args JSON — the poison tear that forbids
 *  continuation. NB: cleanly-closed tools do NOT re-enable continuation — a committed
 *  function_call without its function_call_output cannot ride a continuation input (400) and a
 *  re-emitted call would double-dispatch; eligibility refuses ANY tool use (hasToolUse). */
internal class ToolSalvage {
    private val open = HashSet<Int>()

    val tearOpen: Boolean get() = open.isNotEmpty()

    fun opened(oi: Int) {
        open.add(oi)
    }

    fun closedClean(oi: Int) {
        open.remove(oi)
    }
}
