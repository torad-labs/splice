// NEW: the one spelling of the status line a launch hands Claude Code. The overlay of a wrapped launch and the
// settings file of a head with a tree of its own each wrote the object out, and the timer has to be in both.
package splice.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// why: Claude Code runs the bar's command on its own events, and none comes while a request is open, so a session
// whose turn waits on a limit could not say so; a half-minute timer costs one small local request a tick.
private const val STATUS_LINE_REFRESH_S = 30

/** The `statusLine` setting that runs [command], on Claude Code's events and on a timer between them. */
internal class StatusLineSetting(private val command: String) {
    fun json(): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", command)
        put("padding", 0)
        put("refreshInterval", STATUS_LINE_REFRESH_S)
    }
}
