// NEW: Nov 12, 2026 — the model a command's turns ask for, most of the time. A plan can refuse one model for its week
// while it serves the others, so "where does this command's next turn go" has no one answer without a model. Accounts
// asked with none and marked a login as the one serving while every session of the command ran on another: the marked
// login's own week had room, and its week for the model those sessions use was all used.
//
// THE USUAL MODEL IS ONE ANSWER, AND A WARNING NEEDS THEM ALL (Nov 12, 2026, the same afternoon). Sessions that hand
// their reading to helpers send more helper turns than turns of their own, so for minutes at a time the usual model
// was the helpers' small one. The notice that a command is on its last login read only the usual model, so it would
// have stayed silent about the sessions' own model while helpers ran. [lately] names every model asked for.
//
// A HELPER'S TURN DOES NOT VOTE ON THE USUAL MODEL. The usual model is what Accounts and `splice status` name a
// command's next login for, and what tells the command moving to another login from a helper starting on one. Counted
// by turns it was the helpers' model whenever they were busy, so those screens named a login that had used up the
// model every session was running. A turn Claude Code sends for a subagent says so, and only a session's own turns
// are counted.
package splice.upstream.credentials

import splice.core.util.WallClock

/** The models the last [RECENT] turns of one command's sessions asked for, and every model asked for in the last
 *  hour, by a session or a helper of one. */
public class AskedModels(private val now: WallClock = WallClock { System.currentTimeMillis() }) {
    private val recent = ArrayDeque<String>()
    private val lastAsked = HashMap<String, Long>()

    /** A turn asked for [model]. One that names none is not counted. [helper] is a turn a session's helper sent: its
     *  model is among those asked for [lately], and it has no say in which one is [usual]. */
    @Synchronized
    public fun asked(model: String?, helper: Boolean = false) {
        if (model == null) return
        val at = now()
        lastAsked[model] = at
        lastAsked.values.removeIf { at - it > ASKED_FOR_MS }
        if (helper) return
        if (recent.size == RECENT) recent.removeFirst()
        recent.addLast(model)
    }

    /** The model most of the sessions' recent turns asked for, null before any named one. A tie goes to the later
     *  one. */
    @Synchronized
    public fun usual(): String? {
        val counts = recent.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        return recent.last { counts[it] == most }
    }

    /** Every model a turn asked for in the last [ASKED_FOR_MS], the latest first: the models someone is waiting on
     *  now, however few of the turns were theirs. */
    @Synchronized
    public fun lately(): List<String> {
        val at = now()
        return lastAsked.entries.filter { at - it.value <= ASKED_FOR_MS }.sortedByDescending { it.value }.map { it.key }
    }

    /** Whether a turn of [model] is one of the command's own: it asks for the usual model, names none, or nothing is
     *  usual yet. A helper turn on a small model is not. */
    public fun leads(model: String?): Boolean = model == null || usual().let { it == null || it == model }
}

// why: enough turns that the small requests a session sends beside its own (a title, a summary of a fetched page)
// do not outvote its model.
private const val RECENT = 64

// why: a session that asked for a model and went quiet for a long tool run or a person reading is still on it. After
// an hour with no turn of a model nobody is waiting on it, and its first turn back names it again at once.
private const val ASKED_FOR_MS = 3_600_000L
