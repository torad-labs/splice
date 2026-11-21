// NEW: gateway-no-heads. The file a move would leave, worked out and read back before anything is written: the move is
// made on the bytes the findings came from, and the result must read as splice.toml and hold no finding the file did not
// already hold. A move this cannot place with certainty, or that would leave the file worse, has no candidate.
package splice.app.cli

import splice.core.topology.GatewayMove

internal class MoveFix(private val check: ConfigFindingsOf, private val checked: String) {
    /** The lines of the file with [move] made, taking the value from [line] where the move names none, or null. */
    fun candidate(move: GatewayMove, line: Int?): List<String>? {
        val whole = checked.lines().let { if (checked.endsWith("\n")) it.dropLast(1) else it }
        val mover = TomlMove(whole)
        val value = move.value ?: mover.valueAt(line)
        val moved = value?.let { mover.of(move.table, move.key, it, line.takeUnless { move.keepsSource }) }
        return moved?.takeIf(::readable)
    }

    /** Whether [candidate] reads as splice.toml and says nothing the file did not already say. */
    private fun readable(candidate: List<String>): Boolean {
        val held = check(checked).map { it.path to it.message }.toSet()
        return check(candidate.joinToString("\n", postfix = "\n")).all { (it.path to it.message) in held }
    }
}
