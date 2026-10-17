// NEW: what the client has been shown so far this turn, kept as the wire writes it, so a live view can ask a
// dialect whether a failure RIGHT NOW would still be continued (the Stalled card's "Won't resume"). The facts are the
// two a re-anchor rule turns on that a counter cannot carry: a tool call has opened, and prose has been written.
package splice.core.turn

import java.util.concurrent.atomic.AtomicBoolean

/** Written by the turn's own client writer, read by the live-turn listing from another thread. Never reset: once the
 *  client holds a tool call or prose, the turn does not un-show it, whichever round that was. */
public class WireFacts {
    private val tool = AtomicBoolean(false)
    private val text = AtomicBoolean(false)

    public val toolOpened: Boolean get() = tool.get()
    public val textWritten: Boolean get() = text.get()

    public fun noteTool() {
        tool.set(true)
    }

    public fun noteText() {
        text.set(true)
    }
}
