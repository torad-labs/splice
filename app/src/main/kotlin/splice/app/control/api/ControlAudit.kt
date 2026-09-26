// PORT-OF: ControlServer.kt @ a77531a — invariants unchanged: the three `[control] …` audit lines
// (head action, launch, launch warning) that headAction and launch each wrote through the injected
// LogSink. Single-sourcing the `[control] ` prefix keeps LogSink out of HeadRoutes and LaunchRoutes.
// V4-107: every interpolation now routes through LogSafe, because request bytes (a head key, a
// launch argument, a warning composed from the request body) must not write the audit format itself.
// 2026-09-23: a fourth line, a management route that threw (RouteFailure), with its request line.
package splice.app.control.api

import splice.client.ClaudeArgv
import splice.core.util.LogSafe
import splice.core.util.LogSink

internal class ControlAudit(private val log: LogSink) {
    fun headAction(key: String, action: String) {
        log("[control] head ${LogSafe.str(key)} -> ${LogSafe.str(action)}\n")
    }

    /** V4-257: the argv as ClaudeArgv logs it, every flag and no prompt's text. */
    fun launch(key: String, argv: List<String>) {
        log("[control] launch ${LogSafe.str(key)} -> ${LogSafe.list(ClaudeArgv.promptFree(argv))}\n")
    }

    fun warning(message: String) {
        log("[control] ${LogSafe.str(message)}\n")
    }

    /** A management route that threw (RouteFailure): the request line is caller bytes like the rest. */
    fun routeFailed(method: String, path: String, why: String) {
        log("[control] ${LogSafe.str(method)} ${LogSafe.str(path)} failed: ${LogSafe.str(why)}\n")
    }
}
