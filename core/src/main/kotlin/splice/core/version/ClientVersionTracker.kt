// NEW: v0.4.0 FEATURES.md §2 — daemon-lifetime observations of the Claude Code versions sending requests,
// against the tested version, for the drift warning.
package splice.core.version

import splice.core.GATEWAY_VERSION
import splice.core.TESTED_CLAUDE_CODE
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Sessions remembered at once; a daemon that lives for months sees far more session ids than this,
 *  and the oldest are the ones whose warning was long since shown or moot (review 2026-09-14). */
private const val MAX_SESSIONS = 4_096

/** Daemon-lifetime observations of the Claude Code versions sending message requests. */
public class ClientVersionTracker(
    testedVersion: String = TESTED_CLAUDE_CODE,
    private val parser: ClientVersionParser = ClientVersionParser(),
) {
    private val tested = requireNotNull(parser.parse(testedVersion)) {
        "tested Claude Code version must be dotted numeric: $testedVersion"
    }
    private val observed = ConcurrentHashMap<String, ClientVersion>()

    // why: the whole User-Agent of the newest Claude Code seen, kept because Anthropic's usage endpoint buckets
    // its rate limit by User-Agent and answers a caller that sends none with a 429 that does not recover
    // (anthropics/claude-code#30930). The probe sends what splice SAW, never a string it assembled, so a head
    // that has seen no client sends none.
    @Volatile
    private var newestUserAgent: String? = null
    private val statuslineWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val arrival = ConcurrentLinkedQueue<String>()

    public fun observe(sessionId: String?, userAgent: String?) {
        val session = sessionId?.takeIf(String::isNotBlank) ?: return
        val version = parser.fromUserAgent(userAgent) ?: return
        remember(version, userAgent)
        observed.compute(session) { _, current ->
            if (current == null) arrival.add(session)
            if (current == null || version > current) version else current
        }
        while (observed.size > MAX_SESSIONS) {
            val oldest = arrival.poll() ?: break
            observed.remove(oldest)
            statuslineWarned.remove(oldest)
        }
    }

    /** Keeps [userAgent] when its Claude Code is at least as new as every version seen so far, so a straggler on
     *  an older client never takes the identity back from the newest one. */
    private fun remember(version: ClientVersion, userAgent: String?) {
        if (version >= (observed.values.maxOrNull() ?: version)) newestUserAgent = userAgent
    }

    /** The whole User-Agent of the newest Claude Code this daemon has seen send a turn, or null before any has.
     *  Only a User-Agent whose Claude Code version parsed is ever remembered, so no other tool's identity can
     *  become the one splice presents. */
    public fun newestClaudeCodeUserAgent(): String? = newestUserAgent

    /** How many sessions are remembered right now (bounded by [MAX_SESSIONS]). */
    public fun remembered(): Int = observed.size

    /** One aggregate warning per caller invocation; reading it never consumes another surface. */
    public fun aggregateWarning(): String? = observed.values
        .filter { it > tested }
        .maxOrNull()
        ?.let(::warning)

    /** The warning at most once for this client session during this daemon's lifetime. */
    public fun statuslineWarning(sessionId: String?): String? {
        val session = sessionId?.takeIf(String::isNotBlank) ?: return null
        val version = observed[session]?.takeIf { it > tested } ?: return null
        return warning(version).takeIf { statuslineWarned.add(session) }
    }

    private fun warning(version: ClientVersion): String =
        "Claude Code ${version.wire} is newer than the version splice $GATEWAY_VERSION was tested with (${tested.wire})"
}
