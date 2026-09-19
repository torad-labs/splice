// PORT-OF: ControlServer.kt @ a77531a — invariants unchanged: the three `[control] …` audit lines
// (head action, launch, launch warning) that headAction and launch each wrote through the injected
// LogSink. Single-sourcing the `[control] ` prefix keeps LogSink out of HeadRoutes and LaunchRoutes.
// V4-107: every interpolation now routes through LogSafe, because request bytes (a head key, a
// launch argument, a warning composed from the request body) must not write the audit format itself.
package splice.control.api

import splice.core.util.LogSink
import java.util.Locale

internal class ControlAudit(private val log: LogSink) {
    fun headAction(key: String, action: String) {
        log("[control] head ${LogSafe.str(key)} -> ${LogSafe.str(action)}\n")
    }

    fun launch(key: String, argv: List<String>) {
        log("[control] launch ${LogSafe.str(key)} -> ${LogSafe.list(argv)}\n")
    }

    fun warning(message: String) {
        log("[control] ${LogSafe.str(message)}\n")
    }
}

/** Escapes caller-supplied text before it becomes part of the control log line: a newline forges a
 *  second audit line, a carriage return hides the rest of the real one, and a collection's
 *  toString() is not a record format (an element containing ", " or "]" is read back as a
 *  delimiter). List elements are quoted, control bytes escaped, and every field length-bounded. */
internal object LogSafe {
    // why: a caller field past 200 chars is audit noise, not signal — the cap keeps one request from
    // bloating a line
    private const val MAX_FIELD = 200

    // why: 0x20 is the first printable ASCII, so every code below it is a control char escaped as hex
    private const val SPACE_CODE = 0x20

    // why: 0x7f is DEL, the last control code, so printable text begins above it
    private const val DELETE_CODE = 0x7f

    fun str(value: String): String = escape(value).take(MAX_FIELD)

    fun list(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"" + escape(it).take(MAX_FIELD) + "\"" }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(controlOr(c))
            }
        }
    }

    private fun controlOr(c: Char): String =
        if (c.code < SPACE_CODE || c.code == DELETE_CODE) hexEscape(c.code) else c.toString()

    /** Locale.ROOT, not the default: an audit record whose escape spelling varies by the reader's
     *  locale is not a record. The escaped code is always below 0x100, so four hex digits cover it. */
    private fun hexEscape(code: Int): String = String.format(Locale.ROOT, "\\u%04x", code)
}
