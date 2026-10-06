// NEW: each session's native place or added account, proved by the credential its newest request actually sent.
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

/** Each head and session's newest sent credential identifies its native place or stable added-account label.
 *  Only the private digest and matched label are held in memory, never a token. An unmatched send replaces the
 *  previous match, not the records of other sessions. */
internal class ClaudeCarryingPlaces(
    private val locations: List<ClaudeLoginLocation>,
    private val reads: ClaudeLoginRead,
) {
    private data class Carried(
        val key: String,
        val place: ClaudeLoginPlaceId?,
        val account: String? = place?.wire,
    ) {
        fun matching(candidate: String): Carried? = takeIf { key == candidate && account != null }
    }

    private data class SessionOnHead(val head: String, val session: String)

    private val carried = ConcurrentHashMap<String, Carried>()
    private val sessions = LinkedHashMap<SessionOnHead, Carried>()

    /** Heard on every attempt. Only positive matches are reused: the same previously unmatched credential can
     *  become a native login later. A repeated unmatched send keeps its absence without repeating the diagnostic. */
    fun sent(head: String, session: String?, key: String) {
        val own = session?.let { SessionOnHead(head, it) }
        val previous = own?.let(::remembered) ?: carried[head]
        val known = previous?.matching(key) ?: carried[head]?.matching(key)
        val match = known ?: resolve(head, key)
        carried[head] = match
        if (own != null) remember(own, match)
        if (match.account == null && match != previous) reads.reportUnmatchedCarrying()
    }

    fun carrying(head: String): ClaudeLoginPlaceId? = carried[head]?.place

    /** [session]'s own newest send on [head], or null before it sent or when its credential matches no place:
     *  never the head's, which another session on the same head may have carried. */
    fun carrying(head: String, session: String): ClaudeLoginPlaceId? = remembered(SessionOnHead(head, session))?.place

    /** The stable login label of this session's own newest sent credential, including added pool accounts. */
    fun account(head: String, session: String): String? = remembered(SessionOnHead(head, session))?.account

    fun hasProof(head: String, session: String): Boolean = remembered(SessionOnHead(head, session)) != null

    private fun resolve(head: String, key: String): Carried {
        val place = locations.firstOrNull { it.target.head.key == head && reads.credentialKey(it) == key }?.id
        return Carried(key, place, place?.wire ?: reads.poolAccountForCredential(head, key))
    }

    private fun remembered(session: SessionOnHead): Carried? = synchronized(sessions) { sessions[session] }

    /** Remembers [session]'s match as the most recent, forgetting the least recent past the bound. */
    private fun remember(session: SessionOnHead, match: Carried): Unit = synchronized(sessions) {
        sessions.remove(session)
        sessions[session] = match
        if (sessions.size > REMEMBERED_CARRYING_SESSIONS) sessions.remove(sessions.keys.first())
    }
}
