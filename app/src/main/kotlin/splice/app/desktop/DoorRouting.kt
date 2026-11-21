// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1 and 2); gateway-no-heads. Which provider a model named at
// the door runs on.
//
// A selector, `claude-<provider>--<model id>` or an old one the gateway reads as it (SelectorAliases), runs on the
// provider that serves it, even after a model switch. A named model in the door's picker does too. A tier (such as a
// subagent's "haiku") runs on the session's current provider or the default model of the caller (`splice desktop on`'s
// for Desktop, `splice claude door`'s for the terminal), and Claude Code's small tier, its side calls, runs the model that
// provider names for it, when it names one. Any other model has no route, and the door refuses it.
//
// The terminal's `claude` runs the model a request names (DoorCaller.runsNamedModels). On Nov 17, 2026 seats launched
// with `claude --model claude-opus-5-5` on the door ran on the lane's default model, a Grok, for an hour with nothing
// said; the rule since: a named model runs on the provider that serves it, or is refused by name, and the default never
// stands in for it. Claude Code's start-up probe and its small-tier calls with no served model can still use the
// caller's defaults.
package splice.app.desktop

import splice.app.provider.ServedProvider
import splice.client.models.AnthropicTiers
import splice.core.model.ModelTierSuffix
import splice.core.util.LruSizing

/** Where a door turn runs: its provider, and the ID that provider runs it as. */
internal data class DoorRoute(val provider: ServedProvider, val upstream: String)

internal class DoorRouting {
    private val tiers = AnthropicTiers()

    // Session -> the provider of the last selector it named. Bounded: sessions come and go for the daemon's whole life.
    private val sessions = SessionProviders()

    /** Where [ask] runs for its session, or null when the door does not serve it. A selector runs on its provider, and
     *  the session is on that provider from then. [default] is the selector a session that named none runs, its
     *  caller's. With [named], a served Claude ID also switches providers rather than being treated as a tier on the
     *  old provider. */
    fun of(lineup: DesktopLineup, ask: DoorAsk, default: String?, named: Boolean): DoorRoute? {
        val (provider, model) = lineup.find(ask.model) ?: return tiered(lineup, ask, default, named)
        return onProvider(ask, provider, model.upstream)
    }

    /** [provider] running [ask] as [upstream], with [ask]'s session on [provider] from now. */
    private fun onProvider(ask: DoorAsk, provider: ServedProvider, upstream: String): DoorRoute {
        ask.session?.let { sessions.put(it, provider.key) }
        return DoorRoute(provider, upstream)
    }

    /** An Anthropic ID's tier: run on the session's provider as that provider runs the tier, or else as the default
     *  model, its provider's model for the small tier when it names one. With the default model no longer in the door,
     *  the first provider runs the tier. With [named], only Claude Code's own calls run as the default model; any other
     *  runs the very model it names, where a provider serves it. */
    private fun tiered(lineup: DesktopLineup, ask: DoorAsk, default: String?, named: Boolean): DoorRoute? {
        val base = ModelTierSuffix.strip(ask.model)
        val tier = tiers.tierOf(base) ?: return null
        val native = tiers.modelOf(base)
        val picked = named && !ask.probe
        if (picked && native != null) {
            val exact = served(lineup, ask, native)
            if (exact != null || tier != SMALL_TIER) return exact
        }
        return sideCall(lineup, ask, Tier(tier, native, default, picked))
    }

    /** [tier] when no model of the picker was named for it: on the provider the session is on, else the default
     *  model, which only Claude Code's own calls and its small tier run as when a model is named. */
    private fun sideCall(lineup: DesktopLineup, ask: DoorAsk, tier: Tier): DoorRoute? {
        val held = heldProvider(lineup, ask)
        return when {
            held != null -> onHeld(lineup, held, tier.native, tier.name)
            !tier.picked || tier.name == SMALL_TIER -> defaulted(lineup, tier.name, tier.default)
            else -> null
        }
    }

    /** The provider [ask]'s session is on, or null for a session on none. */
    private fun heldProvider(lineup: DesktopLineup, ask: DoorAsk): ServedProvider? =
        ask.session?.let(sessions::get)?.let { key -> lineup.providers.firstOrNull { it.key == key } }

    /** [tier] on the [provider] the session is on: the very model it names where [provider] serves it, else the
     *  provider's model for the tier, else its pinned model. A provider with no tier for it ran its pinned model, which
     *  on one that declares none is the first model it lists: on Nov 17, 2026 every Opus turn of the door's sessions on
     *  the claude head ran as Fable 5, spent on every account, and was held for two days. */
    private fun onHeld(lineup: DesktopLineup, provider: ServedProvider, native: String?, tier: String): DoorRoute {
        val exact = native?.let { id -> lineup.models.firstOrNull { it.provider == provider.key && names(it, id) } }
        return DoorRoute(provider, exact?.upstream ?: provider.tiers[tier] ?: lineup.pinnedId(provider))
    }

    /** A picker ID picks the same provider here that supplied it to discovery, even when another serves the ID. */
    private fun served(lineup: DesktopLineup, ask: DoorAsk, native: String): DoorRoute? {
        val row = lineup.picker.firstOrNull { names(it, native) }
            ?: lineup.models.firstOrNull { names(it, native) }
            ?: return null
        return lineup.providers.firstOrNull { it.key == row.provider }?.let { onProvider(ask, it, row.upstream) }
    }

    private fun names(row: DoorModel, native: String): Boolean =
        tiers.modelOf(ModelTierSuffix.strip(row.upstream)) == native

    /** [tier] in a session that named no selector: the default model, or its provider's model for the small tier; the
     *  first provider's model for [tier] when the default model is no longer in the door. */
    private fun defaulted(lineup: DesktopLineup, tier: String, default: String?): DoorRoute? {
        val chosen = default?.let(lineup::find)
            ?: return lineup.providers.firstOrNull()?.let { DoorRoute(it, it.tiers[tier] ?: lineup.pinnedId(it)) }
        val (provider, model) = chosen
        val small = provider.tiers[SMALL_TIER]?.takeIf { tier == SMALL_TIER }
        return DoorRoute(provider, small ?: model.upstream)
    }
}

/** A tier a request names: its [name], the Claude model it stands for ([native]), the caller's [default] selector and
 *  whether the request named a model of the picker ([picked]). */
private data class Tier(val name: String, val native: String?, val default: String?, val picked: Boolean)

// Claude Code's tier for its side calls (titles, summaries), which a provider may map to a smaller model of its own.
private const val SMALL_TIER = "haiku"

/** Each session's provider: the one of the last selector it named, for the tiers it asks for later. The session heard
 *  from longest ago goes first. */
private class SessionProviders {
    private val providers = object : LinkedHashMap<String, String>(INITIAL, LruSizing.LOAD_FACTOR, ORDER) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > SESSIONS_KEPT
    }

    fun put(session: String, provider: String) = synchronized(providers) { providers[session] = provider }

    fun get(session: String): String? = synchronized(providers) { providers[session] }
}

// why: a few thousand Code tab sessions is more than a person opens between two daemon restarts.
private const val SESSIONS_KEPT = 4_096

// The JDK's own starting size: the map is bounded by its eviction rule, not its capacity.
private const val INITIAL = LruSizing.INITIAL_CAPACITY

// A map in access order, so the session heard from longest ago is the eldest.
private const val ORDER = true
