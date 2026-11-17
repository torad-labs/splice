// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1 and 2). Which head a model named at the door runs on.
//
// A selector, the ID a head's own /v1/models lists, runs that model on that head, and the session is on that head
// from then. Anthropic's own IDs are a tier (AnthropicTiers), which is all Desktop's own picker sends: in a session
// that named a selector they run the model its head names for that tier, or its pinned model; in any other they run the
// default model `splice desktop on` chose, and Claude Code's small tier, its side calls, runs the model that model's
// head names for it, when it names one. Any other model has no route, and the door refuses it.
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

    /** An Anthropic ID's tier: run on [session]'s head as that head runs the tier, or else as the default model, its
     *  head's model for the small tier when it names one. With the default model no longer in the door, the first
     *  head runs the tier. */
    private fun tiered(lineup: DesktopLineup, asked: String, session: String?): DoorRoute? {
        val tier = tiers.tierOf(asked) ?: return null
        val held = session?.let(sessions::get)?.let { key -> lineup.heads.firstOrNull { it.key == key } }
        return if (held != null) DoorRoute(held, held.tiers[tier] ?: held.pinned) else defaulted(lineup, tier)
    }

    /** [tier] in a session that named no selector: the default model, or its head's model for the small tier; the
     *  first head's model for [tier] when the default model is no longer in the door. */
    private fun defaulted(lineup: DesktopLineup, tier: String): DoorRoute? {
        val chosen = records.get().defaultModel?.let(lineup::find)
            ?: return lineup.heads.firstOrNull()?.let { DoorRoute(it, it.tiers[tier] ?: it.pinned) }
        val (head, model) = chosen
        val small = head.tiers[SMALL_TIER]?.takeIf { tier == SMALL_TIER }
        return DoorRoute(head, small ?: model.upstream)
    }
}

// Claude Code's tier for its side calls (titles, summaries), which a head may map to a smaller model of its own.
private const val SMALL_TIER = "haiku"

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
