// PORT-OF: splice/control/api/ControlAudit.kt (LogSafe) @ a850540f — invariants unchanged: the same
// escaping, byte for byte (CR/LF/control bytes escaped, list elements quoted, every field bounded),
// and V4-107's rule still reads it by name (any expression beginning `LogSafe.`).
//
// Shared by the control HTTP adapter and the independent MCP host. Pure text escaping lives below
// both integrations so neither imports the other just to render a safe diagnostic.
package splice.core.util

import java.util.Locale

/** Escapes caller-supplied text before it becomes part of the control log line: a newline forges a
 *  second audit line, a carriage return hides the rest of the real one, and a collection's
 *  toString() is not a record format (an element containing ", " or "]" is read back as a
 *  delimiter). List elements are quoted, control bytes escaped, and every field length-bounded. */
public object LogSafe {
    // why: a caller field past 200 chars is audit noise, not signal — the cap keeps one request from
    // bloating a line
    private const val MAX_FIELD = 200

    // why: 0x20 is the first printable ASCII, so every code below it is a control char escaped as hex
    private const val SPACE_CODE = 0x20

    // why: 0x7f is DEL, the last control code, so printable text begins above it
    private const val DELETE_CODE = 0x7f

    public fun str(value: String): String = escape(value).take(MAX_FIELD)

    public fun list(values: List<String>): String =
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
