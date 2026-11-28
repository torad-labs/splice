package splice.head.observe

import splice.core.turn.ObservedTurn
import splice.head.wire.Utf8Length

private const val EVENT_PREFIX = "event: "

/** The tap already has the final frame. Read its first line, never the JSON or its content. */
internal object TurnObserverFrames {
    fun frame(observed: ObservedTurn, frame: String) {
        if (!observed.wantsFrames || !frame.startsWith(EVENT_PREFIX)) return
        val newline = frame.indexOf('\n', EVENT_PREFIX.length)
        if (newline <= EVENT_PREFIX.length) return
        observed.frame(frame.substring(EVENT_PREFIX.length, newline), Utf8Length.of(frame))
    }
}
