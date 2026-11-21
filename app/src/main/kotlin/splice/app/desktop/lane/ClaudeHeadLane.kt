// NEW: door-default (docs/specs/door-default.md, decision 9); gateway-no-heads. `claude` on its head carries the door's
// models. The head whose command is `claude` reads the model each request names (OtherHeads): a name that starts as a
// selector of another provider does is that provider's, and every other name stays on the head, which sends it to
// Anthropic with the caller's own login exactly as before, so a model Anthropic ships tomorrow runs the day it ships. A
// selector the door serves is served by its provider as the door serves it (DoorRelay), with that provider's own login,
// cooldowns and failures; the caller's Claude login never goes with it. A name of that shape the door does not serve is
// refused by name, saying why, with nothing sent anywhere.
//
// The window: a session holds Claude Code's own window for a model it does not know, 200,000 tokens, or 1,000,000 for
// a selector that ends in `[1m]`, so a served turn's counts are reported as the same share of that window that they
// are of the model's own, as the door reports them.
package splice.app.desktop.lane

import io.ktor.server.application.ApplicationCall
import splice.app.desktop.DoorAsk
import splice.app.desktop.DoorCaller
import splice.app.desktop.DoorRelay
import splice.app.desktop.DoorRoute
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.SelectorAliases
import splice.client.models.AnthropicTiers
import splice.core.model.ClientModelNames
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.Topology
import splice.core.turn.CLAUDE_DOOR
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.head.OtherAnswer
import splice.head.OtherAsk
import splice.head.OtherHeads
import splice.share.RefusalVoice

internal class ClaudeHeadLane(private val topology: Topology, private val aliases: SelectorAliases) {
    private val tiers = AnthropicTiers()
    private val toProvider = DoorRelay(WallClock(System::currentTimeMillis))

    /** The door's providers once [open] has run; null before, when every request stays on the head. */
    @Volatile
    private var opened: Opened? = null

    /** From now on a model of [providers]' named on the head is served by them. */
    fun open(providers: DesktopProviders) {
        opened = Opened(providers)
    }

    /** What the head keyed [head] hands to other providers: the door's models when its command is `claude`, and
     *  nothing for any other head. */
    fun forHead(head: String): OtherHeads {
        if (head !in topology.resolveHeadKeys(CLAUDE_COMMAND)) return OtherHeads { null }
        val own = topology.heads[head]?.provider.orEmpty()
        return OtherHeads { ask ->
            val door = opened?.takeIf { providerOf(ask.model)?.let { it != own } == true }
            door?.let { OtherAnswer { call -> it.answer(call, ask) } }
        }
    }

    /** The provider whose selectors or readable names [model] starts as; null for an Anthropic ID or any other name,
     *  which stays on the head. */
    private fun providerOf(model: String): String? {
        if (tiers.tierOf(model) != null) return null
        return aliases.of(model)?.provider
            ?: readable.entries.filter { (prefix, _) -> model.startsWith(prefix) }
                .maxByOrNull { it.key.length }?.value
    }

    /** The prefixes a provider's readable names start with, each with the provider: the ones ClientModelNames makes
     *  from its selectors' prefix when that names another lab, and a head's own, where splice.toml still has one. */
    private val readable: Map<String, String> = buildMap {
        topology.providers.keys.forEach { key -> readableOf("claude-$key--")?.let { put(it, key) } }
        topology.heads.values.forEach { head ->
            val given = head.claude.readable?.prefix?.takeIf { it.isNotEmpty() }
            (given ?: readableOf(head.discoveryPrefix))?.let { putIfAbsent(it, head.provider) }
        }
    }

    private fun readableOf(prefix: String): String? =
        ClientModelNames.prefixOf(prefix).takeIf { it.isNotEmpty() && it != prefix }

    private inner class Opened(private val providers: DesktopProviders) {
        private val voice = RefusalVoice(CLAUDE_ERROR_PREFIX)

        /** [ask] served by the provider that serves its model, or refused by name when none does. */
        suspend fun answer(call: ApplicationCall, ask: OtherAsk) {
            val lineup = providers.lineup()
            val (provider, model) = lineup.find(ask.model)
                ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, notServed(lineup, ask.model))
            val caller = HeadLaneCaller(held(ask.model))
            val asked = DoorAsk(ask.request, ask.model, ask.session)
            toProvider.send(call, caller, DoorRoute(provider, model.upstream), asked, ask.counting)
        }

        /** Why [model] runs nowhere: its provider is left out of the door, with the reason, or lists no such model. */
        private fun notServed(lineup: DesktopLineup, model: String): String {
            val key = providerOf(model)
            val out = lineup.leftOut.firstOrNull { it.provider == key }
            if (out != null) return "model \"$model\" does not run now, as ${out.provider} is left out: ${out.reason}"
            return "model \"$model\" is no model of $key; `splice claude` lists the models claude runs"
        }
    }

    /** The window a session holds for [model]: the one Claude Code holds for a model it does not know. */
    private fun held(model: String): Long =
        if (model.endsWith(ONE_MILLION_SUFFIX, ignoreCase = true)) HELD_1M else HELD

    /** The head lane as a caller of the door: no key opens the door to it, since its turns come through the head, and
     *  its sessions hold [window] for the model the turn names. */
    private class HeadLaneCaller(private val window: Long) :
        DoorCaller(RefusalVoice(CLAUDE_ERROR_PREFIX), CLAUDE_DOOR) {
        override fun presents(header: String?): Boolean = false

        override fun closed(): String? = null

        override fun default(lineup: DesktopLineup): String? = null

        override fun window(): Long = window

        override val noHead: String = "no account is signed in, so claude runs Claude's models alone"

        override val notInDoor: String = "is not a model claude runs; `splice claude` lists them"
    }
}

/** What Claude Code holds for a model it does not know (Claude Code 2.1.326). */
private const val HELD = 200_000L

// why: a model whose name ends in `[1m]` gets a million tokens from Claude Code (2.1.326), whatever the model.
private const val HELD_1M = 1_000_000L
private const val ONE_MILLION_SUFFIX = "[1m]"
