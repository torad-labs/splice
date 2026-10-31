// PORT-OF: ControlServer.kt @ a77531a — invariants unchanged: the three `[control] …` audit lines
// (head action, launch, launch warning) that headAction and launch each wrote through the injected
// LogSink. Single-sourcing the `[control] ` prefix keeps LogSink out of HeadRoutes and LaunchRoutes.
// V4-107: every interpolation now routes through LogSafe, because request bytes (a head key, a
// launch argument, a warning composed from the request body) must not write the audit format itself.
// 2026-09-23: a fourth line, a management route that threw (RouteFailure), with its request line.
// 2026-10-13: an inline JSON argument (the --settings body) is logged as its size and a short hash. Logged whole, about
// 1,400 launches an hour rolled daemon.log at 64 MB within hours (splice-turns log audit via splice-lead); the size and
// hash still tell one launch's settings from another's.
// 2026-10-13: a launch the same as the one before it for that key is not logged again. A `claude agents --json` run
// through the launch recipe twice every ten seconds still filled daemon.log on its own; the next line
// that differs names how many identical launches it stood for (splice-lead, from splice-usage's log volume count).
package splice.app.control.api

import splice.client.ClaudeArgv
import splice.core.perf.InputDigest
import splice.core.util.LogSafe
import splice.core.util.LogSink

// why: twelve hex characters tell launches apart in one log without carrying the body.
private const val HASH_CHARS = 12

internal class ControlAudit(private val log: LogSink) {
    /** Per launch key, the argv last logged and how many identical launches have come since. */
    private val lastLaunch = HashMap<String, Pair<List<String>, Int>>()

    fun headAction(key: String, action: String) {
        log("[control] head ${LogSafe.str(key)} -> ${LogSafe.str(action)}\n")
    }

    /** V4-257: the argv as ClaudeArgv logs it, every flag and no prompt's text, with each inline JSON argument as
     *  `<json N bytes sha256:xxxxxxxxxxxx>`. Logged only when it differs from the key's last logged launch. */
    fun launch(key: String, argv: List<String>) {
        val shown = ClaudeArgv.promptFree(argv).map { arg ->
            if (!arg.startsWith("{")) return@map arg
            "<json ${arg.toByteArray().size} bytes sha256:${InputDigest.hex(arg).take(HASH_CHARS)}>"
        }
        val repeats = synchronized(lastLaunch) {
            val last = lastLaunch[key]
            if (last?.first == shown) {
                lastLaunch[key] = shown to last.second + 1
                return
            }
            lastLaunch[key] = shown to 0
            last?.second ?: 0
        }
        val since = if (repeats > 0) " ($repeats identical launches since the last line)" else ""
        log("[control] launch ${LogSafe.str(key)} -> ${LogSafe.list(shown)}${LogSafe.str(since)}\n")
    }

    fun warning(message: String) {
        log("[control] ${LogSafe.str(message)}\n")
    }

    /** A management route that threw (RouteFailure): the request line is caller bytes like the rest. */
    fun routeFailed(method: String, path: String, why: String) {
        log("[control] ${LogSafe.str(method)} ${LogSafe.str(path)} failed: ${LogSafe.str(why)}\n")
    }
}
