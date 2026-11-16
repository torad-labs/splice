// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1 and 2). Which head a model named at the door runs on.
//
// A selector, the ID a head's own /v1/models lists, runs that model on that head, and the session is on that head
// from then. Anthropic's own IDs are a tier (AnthropicTiers): they run the model the session's head names for that
// tier, or its pinned model. Any other model has no route, and the door refuses it.
package splice.app.desktop

import splice.client.models.AnthropicTiers
import splice.core.util.LruSizing

/** Where a door turn runs: its head, and the ID that head runs it as. */
internal data class DoorRoute(val head: DoorHead, val upstream: String)

internal class DoorRouting(private val records: DesktopRecords) {
    private val tiers = AnthropicTiers()

    // Session -> the head of the last selector it named. Bounded: sessions come and go for the daemon's whole life.
    private val sessions = SessionHeads()

    /** Where [asked] runs for [session], or null when the door does not serve it. A selector runs on its head, and
     *  the session is on that head from then. */
    fun of(lineup: DesktopLineup, asked: String, session: String?): DoorRoute? {
        val (head, model) = lineup.find(asked) ?: return tiered(lineup, asked, session)
        if (session != null) sessions.put(session, head.key)
        return DoorRoute(head, model.upstream)
    }

    /** An Anthropic ID's tier, run on [session]'s head, else the default model's, as that head runs the tier. */
    private fun tiered(lineup: DesktopLineup, asked: String, session: String?): DoorRoute? {
        val tier = tiers.tierOf(asked) ?: return null
        val held = session?.let(sessions::get)
        val head = lineup.heads.firstOrNull { it.key == held } ?: defaultHead(lineup) ?: return null
        return DoorRoute(head, head.tiers[tier] ?: head.pinned)
    }

    /** The head of the default model, or the first head in the door when that model is no longer in it. */
    private fun defaultHead(lineup: DesktopLineup): DoorHead? =
        records.get().defaultModel?.let { lineup.find(it)?.first } ?: lineup.heads.firstOrNull()
}

/** Each session's head: the head of the last selector it named, for the tiers it asks for later. The session heard
 *  from longest ago goes first. */
private class SessionHeads {
    private val heads = object : LinkedHashMap<String, String>(INITIAL, LruSizing.LOAD_FACTOR, ORDER) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > SESSIONS_KEPT
    }

    fun put(session: String, head: String) = synchronized(heads) { heads[session] = head }

    fun get(session: String): String? = synchronized(heads) { heads[session] }
}

// why: a few thousand Code tab sessions is more than a person opens between two daemon restarts.
private const val SESSIONS_KEPT = 4_096

// The JDK's own starting size: the map is bounded by its eviction rule, not its capacity.
private const val INITIAL = LruSizing.INITIAL_CAPACITY

// A map in access order, so the session heard from longest ago is the eldest.
private const val ORDER = true
