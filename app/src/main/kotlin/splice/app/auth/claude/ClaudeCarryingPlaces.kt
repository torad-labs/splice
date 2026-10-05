// NEW: which native place carries each head's requests, read from the credential each request actually sent.
// 2026-10-04: claude-splice's requests carried the ~/.claude login while Models and Usage read the command's own
// folder, so the console showed a stale 59% beside an account at 91%.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceId
import java.util.concurrent.ConcurrentHashMap

/** Per head, the place whose live credential its newest matched request carried. Only the digest splice already
 *  files readings under is held, never a token, and only in memory: after a restart no head has a match until its
 *  first request is sent. A digest that matches no place (another login, or a token already rotated out of its
 *  file) leaves the last match standing. */
internal class ClaudeCarryingPlaces(
    private val locations: List<ClaudeLoginLocation>,
    private val reads: ClaudeLoginRead,
) {
    private data class Carried(val key: String, val place: ClaudeLoginPlaceId)

    private val carried = ConcurrentHashMap<String, Carried>()

    /** Heard on every attempt; the places' files are read only when the digest is not the head's current match. */
    fun sent(head: String, key: String) {
        if (carried[head]?.key == key) return
        val place = locations.firstOrNull { it.target.head.key == head && reads.credentialKey(it) == key } ?: return
        carried[head] = Carried(key, place.id)
    }

    fun carrying(head: String): ClaudeLoginPlaceId? = carried[head]?.place
}
