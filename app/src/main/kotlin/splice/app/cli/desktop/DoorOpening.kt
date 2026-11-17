// NEW: door-default (docs/specs/door-default.md, decisions 1 and 8). A daemon that has just started answers before its
// heads are up, and its door opens once they are: a command that has just started it, `splice desktop`, `splice
// claude` or `splice setup` linking `claude`, asks again while the daemon says the door is still opening.
package splice.app.cli.desktop

import splice.core.wire.HttpStatus
import splice.daemonclient.ControlReply

/** One ask of the daemon: its reply, or null when none came. */
internal fun interface DoorAsk {
    operator fun invoke(): ControlReply?
}

/** One ask of a daemon, sent again while it answers 503, which changed nothing, so sending again is safe. Bounded, so
 *  a door that never opens is said in the daemon's own words. */
internal class DoorOpening(private val forMs: Long = OPENING_MS) {
    fun reply(send: DoorAsk): ControlReply? {
        val until = System.currentTimeMillis() + forMs
        var reply = send()
        while (reply?.status == HttpStatus.SERVICE_UNAVAILABLE && System.currentTimeMillis() < until) {
            Thread.sleep(OPENING_POLL_MS)
            reply = send()
        }
        return reply
    }
}

// why: a daemon's heads come up within seconds of its start; a door still shut after this is said as it is
private const val OPENING_MS = 30_000L

// why: a door opens within a second or two of its heads; four asks a second see it soon and cost the daemon nothing
private const val OPENING_POLL_MS = 250L
