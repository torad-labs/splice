// PORT-OF: ControlServer.kt @ a77531a — invariants unchanged: the three `[control] …` audit lines
// (head action, launch, launch warning) that headAction and launch each wrote through the injected
// LogSink. Single-sourcing the `[control] ` prefix keeps LogSink out of HeadRoutes and LaunchRoutes.
package splice.control.api

import splice.core.util.LogSink

internal class ControlAudit(private val log: LogSink) {
    fun headAction(key: String, action: String) {
        log("[control] head $key -> $action\n")
    }

    fun launch(key: String, argv: List<String>) {
        log("[control] launch $key -> $argv\n")
    }

    fun warning(message: String) {
        log("[control] $message\n")
    }
}
