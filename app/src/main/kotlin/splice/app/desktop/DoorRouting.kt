// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1 and 2). Which head a model named at the door runs on.
//
// A selector, the ID a head's own /v1/models lists, runs that model on that head, and the session is on that head
// from then. Anthropic's own IDs are a tier (AnthropicTiers), which is all Desktop's own picker sends: in a session
// that named a selector they run the model its head names for that tier, or its pinned model; in any other they run the
// default model of the caller (`splice desktop on`'s for Desktop, `splice claude door`'s for the terminal), and Claude Code's small tier, its side calls, runs the model that model's
// head names for it, when it names one. Any other model has no route, and the door refuses it.
//
// The terminal's `claude` runs the model a request names (DoorCaller.runsNamedModels). On Nov 17, 2026 seats launched
// with `claude --model claude-opus-5-5` on the door ran on the lane's default model, a Grok, for an hour with nothing
// said; splice's lead, for Marcos, set the rule: a named model runs on the head that serves it, or is refused by name,
// and the default never stands in for it. So in a session on no head yet, an Anthropic ID runs on the head that serves
// that very model (the claude head, on the Claude accounts added to splice), and the session is on that head from
// then. Where none serves it, it is refused by name, save Claude Code's own calls: its start-up check and its small
// tier, which run as they do for Desktop. A session already on a head runs that head's model for the tier, as above.
package splice.app.desktop

import splice.client.models.AnthropicTiers
import splice.core.util.LruSizing

/** Where a door turn runs: its head, and the ID that head runs it as. */
internal data class DoorRoute(val head: DoorHead, val upstream: String)

internal class DoorRouting {
    private val tiers = AnthropicTiers()

    // Session -> the head of the last selector it named. Bounded: sessions come and go for the daemon's whole life.
    private val sessions = SessionHeads()

    /** Where [ask] runs for its session, or null when the door does not serve it. A selector runs on its head, and
     *  the session is on that head from then. [default] is the selector a session that named none runs, its caller's.
     *  With [named], an Anthropic ID in a session on no head runs the very model it names, or has no route. */
    fun of(lineup: DesktopLineup, ask: DoorAsk, default: String?, named: Boolean): DoorRoute? {
        val (head, model) = lineup.find(ask.model) ?: return tiered(lineup, ask, default, named)
        return onHead(ask, head, model.upstream)
    }

    /** [head] running [ask] as [upstream], with [ask]'s session on [head] from now. */
    private fun onHead(ask: DoorAsk, head: DoorHead, upstream: String): DoorRoute {
        ask.session?.let { sessions.put(it, head.key) }
        return DoorRoute(head, upstream)
    }

    /** An Anthropic ID's tier: run on the session's head as that head runs the tier, or else as the default model, its
     *  head's model for the small tier when it names one. With the default model no longer in the door, the first
     *  head runs the tier. With [named], only Claude Code's own calls run as the default model; any other runs the
     *  very model it names, where a head serves it. */
    private fun tiered(lineup: DesktopLineup, ask: DoorAsk, default: String?, named: Boolean): DoorRoute? {
        val tier = tiers.tierOf(ask.model) ?: return null
        val held = ask.session?.let(sessions::get)?.let { key -> lineup.heads.firstOrNull { it.key == key } }
        return when {
            held != null -> DoorRoute(held, held.tiers[tier] ?: held.pinned)
            !named || ask.probe -> defaulted(lineup, tier, default)
            else -> served(lineup, ask) ?: defaulted(lineup, tier, default).takeIf { tier == SMALL_TIER }
        }
    }

    /** The head that serves the very model [ask] names, run as its selector would be; null when no head does. */
    private fun served(lineup: DesktopLineup, ask: DoorAsk): DoorRoute? {
        val named = tiers.modelOf(ask.model) ?: return null
        return lineup.models.firstOrNull { tiers.modelOf(it.upstream) == named }
            ?.let { lineup.find(it.model) }
            ?.let { (head, model) -> onHead(ask, head, model.upstream) }
    }

    /** [tier] in a session that named no selector: the default model, or its head's model for the small tier; the
     *  first head's model for [tier] when the default model is no longer in the door. */
    private fun defaulted(lineup: DesktopLineup, tier: String, default: String?): DoorRoute? {
        val chosen = default?.let(lineup::find)
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
