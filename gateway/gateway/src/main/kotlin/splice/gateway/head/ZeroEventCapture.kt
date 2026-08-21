// PORT-OF: splice/gateway/head/TearAwareEvents.kt (ZeroEventCapture) @ 86f1411 — invariants
// unchanged: raw-body capture for the G2 zero-event classifier. Own file (concentration,
// 2026-08-19) so TearAwareEvents is not billed for a second column-0 type.
package splice.gateway.head

private const val ZERO_EVENT_SNIPPET_CHARS = 1024

/** Raw-body capture for the G2 zero-event classifier, threaded through [TearAwareEvents.run]. */
internal class ZeroEventCapture {
    var sawEvent = false
    var malformedLogged = false
    val snippet = StringBuilder(ZERO_EVENT_SNIPPET_CHARS)

    /** Keep the first [ZERO_EVENT_SNIPPET_CHARS] of raw text until an event arrives. */
    fun appendRaw(text: CharSequence): Boolean {
        val room = ZERO_EVENT_SNIPPET_CHARS - snippet.length
        if (!sawEvent && room > 0) {
            snippet.append(text, 0, minOf(text.length, room))
        }
        return !sawEvent && snippet.length < ZERO_EVENT_SNIPPET_CHARS
    }
}
