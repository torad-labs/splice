// NEW: gateway-no-heads. The file a move would leave, worked out and read back before anything is written: the move is
// made on the bytes the findings came from, and the result must read as splice.toml and hold no finding the file did not
// already hold. A move this cannot place with certainty, or that would leave the file worse, has no candidate, and the
// session offers none. A move with companions (a head's standing prompt: text, file and mode) takes each from its own
// statement in the same table and puts them all in one candidate, since moving any one alone leaves the rest a new
// warning. A value is taken as the file wrote it, over every line a multi-line string runs to, and its comment with it.
package splice.app.cli

import splice.core.topology.GatewayMove

internal class MoveFix(private val check: ConfigFindingsOf, private val checked: String) {
    /** The lines of the file with [move] made, taking the value from [line] where the move names none, or null. */
    fun candidate(move: GatewayMove, line: Int?): List<String>? {
        val whole = checked.lines().let { if (checked.endsWith("\n")) it.dropLast(1) else it }
        val mover = TomlMove(whole)
        val beside = move.companions.map { key -> line?.let { mover.lineOf(key, it) } }
        val named = move.value?.let { TomlStatements.Written(it, "") } ?: mover.valueAt(line)
        val values = listOf(named) + beside.map { at -> at?.let(mover::valueAt) }
        if (values.any { it == null }) return null
        val entries = move.keys.zip(values) { key, said -> TomlMove.Entry(key, requireNotNull(said)) }
        val removed = if (move.keepsSource) emptySet() else setOfNotNull(line) + beside.filterNotNull()
        return mover.of(move.table, entries, removed)?.takeIf(::readable)
    }

    /** Whether [candidate] reads as splice.toml and says nothing the file did not already say. */
    private fun readable(candidate: List<String>): Boolean {
        val held = check(checked).map { it.path to it.message }.toSet()
        return check(candidate.joinToString("\n", postfix = "\n")).all { (it.path to it.message) in held }
    }
}
