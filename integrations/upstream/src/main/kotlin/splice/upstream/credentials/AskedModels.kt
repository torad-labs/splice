// NEW: Nov 12, 2026 — the model a command's turns ask for, most of the time. A plan can refuse one model for its week
// while it serves the others, so "where does this command's next turn go" has no one answer without a model. Accounts
// asked with none and marked a login as the one serving while every session of the command ran on another: the marked
// login's own week had room, and its week for the model those sessions use was all used.
package splice.upstream.credentials

/** The models the last [RECENT] turns of one command asked for. */
public class AskedModels {
    private val recent = ArrayDeque<String>()

    /** A turn asked for [model]. One that names none is not counted. */
    @Synchronized
    public fun asked(model: String?) {
        if (model == null) return
        if (recent.size == RECENT) recent.removeFirst()
        recent.addLast(model)
    }

    /** The model most of the recent turns asked for, null before any named one. A tie goes to the later one. */
    @Synchronized
    public fun usual(): String? {
        val counts = recent.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        return recent.last { counts[it] == most }
    }
}

// why: enough turns that a burst of helper turns on a small model does not outvote the sessions' own model.
private const val RECENT = 64
