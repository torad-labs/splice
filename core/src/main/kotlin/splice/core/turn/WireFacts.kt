// NEW: what the client has been shown so far this turn, kept as the wire writes it, so a live view can ask a
// dialect whether a failure RIGHT NOW would still be continued (the Stalled card's "Won't resume"). The facts are the
// two a re-anchor rule turns on that a counter cannot carry: a tool call has opened, and prose has been written.
// The tool calls' own ids are kept too (2026-10-19): a turn that ends clean on them is how the daemon knows a session
// is inside a tool until the client's next request answers each id.
package splice.core.turn

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// why: one turn shows a client a handful of parallel calls; the bound keeps a runaway stream from growing the list.
private const val MAX_TOOL_IDS = 256

/** Written by the turn's own client writer, read by the live-turn listing from another thread. Never reset: once the
 *  client holds a tool call or prose, the turn does not un-show it, whichever round that was. */
public class WireFacts {
    private val tool = AtomicBoolean(false)
    private val text = AtomicBoolean(false)
    private val toolIds = CopyOnWriteArrayList<String>()

    public val toolOpened: Boolean get() = tool.get()
    public val textWritten: Boolean get() = text.get()

    /** The ids of the tool calls the client was shown, in the order they opened, blank ids left out. */
    public val toolUseIds: List<String> get() = toolIds.toList()

    public fun noteTool(id: String) {
        tool.set(true)
        if (id.isNotEmpty() && toolIds.size < MAX_TOOL_IDS) toolIds.add(id)
    }

    public fun noteText() {
        text.set(true)
    }
}
