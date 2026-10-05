// NEW: which native place carries each head's requests, read from the credential each request actually sent.
// 2026-10-04: claude-splice's requests carried the ~/.claude login while Models and Usage read the command's own
// folder, so the console showed a stale 59% beside an account at 91%.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceId
import java.util.concurrent.ConcurrentHashMap

// why: how many sessions keep their newest match. Claude Code tells the daemon of no session's end, so a session
// leaves this record once this many others have sent since its newest request, and it reads null again until its next
// request matches. Sized at the bound the console's own session record uses (ConsoleWiring's REMEMBERED_SESSIONS),
// well above the sessions one daemon serves at once.
private const val REMEMBERED_CARRYING_SESSIONS = 4096

/** Per head, and per session on that head, the place whose live credential its newest matched request carried. Only
 *  the digest splice already files readings under is held, never a token, and only in memory: after a restart no head
 *  or session has a match until its first request is sent. A digest that matches no place (another login, or a token
 *  already rotated out of its file) leaves the last match standing. */
internal class ClaudeCarryingPlaces(
    private val locations: List<ClaudeLoginLocation>,
    private val reads: ClaudeLoginRead,
) {
    private data class Carried(val key: String, val place: ClaudeLoginPlaceId)

    private data class SessionOnHead(val head: String, val session: String)

    private val carried = ConcurrentHashMap<String, Carried>()
    private val sessions = LinkedHashMap<SessionOnHead, Carried>()

    /** Heard on every attempt. The places' files are read only when the digest is neither the session's nor the head's
     *  current match; an equal digest is the same credential, so it names the same place whoever sent it first. */
    fun sent(head: String, session: String?, key: String) {
        val own = session?.let { SessionOnHead(head, it) }
        val known = own?.let(::remembered)?.takeIf { it.key == key } ?: carried[head]?.takeIf { it.key == key }
        val match = known
            ?: locations.firstOrNull { it.target.head.key == head && reads.credentialKey(it) == key }
                ?.let { Carried(key, it.id) }
            ?: return
        carried[head] = match
        if (own != null) remember(own, match)
    }

    fun carrying(head: String): ClaudeLoginPlaceId? = carried[head]?.place

    /** [session]'s own newest match on [head], or null before it has one: never the head's, which another session on
     *  the same head may have carried. */
    fun carrying(head: String, session: String): ClaudeLoginPlaceId? = remembered(SessionOnHead(head, session))?.place

    private fun remembered(session: SessionOnHead): Carried? = synchronized(sessions) { sessions[session] }

    /** Remembers [session]'s match as the most recent, forgetting the least recent past the bound. */
    private fun remember(session: SessionOnHead, match: Carried): Unit = synchronized(sessions) {
        sessions.remove(session)
        sessions[session] = match
        if (sessions.size > REMEMBERED_CARRYING_SESSIONS) sessions.remove(sessions.keys.first())
    }
}
