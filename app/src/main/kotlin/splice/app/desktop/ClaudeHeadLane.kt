// NEW: door-default (docs/specs/door-default.md, decision 9). `claude` on its head carries the door's models. The head
// whose command is `claude` reads the model each request names (OtherHeads): a name that starts as another head's
// selectors do is that head's, and every other name stays on the head, which sends it to Anthropic with the caller's
// own login exactly as before, so a model Anthropic ships tomorrow runs the day it ships. A selector the door serves is
// relayed to its head as the door relays it (DoorRelay), with that head's own login, cooldowns and failures; the
// caller's Claude login never goes with it, since the relay sends the head's turn key in its place (ShareRelay). A
// name of that shape the door does not serve is refused by name, saying why, with nothing sent anywhere.
//
// The window: a session holds Claude Code's own window for a model it does not know, 200,000 tokens, or 1,000,000 for
// a selector that ends in `[1m]`, so a relayed turn's counts are reported as the same share of that window that they
// are of the model's own, as the door reports them.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import splice.client.models.AnthropicTiers
import splice.core.model.ClientModelNames
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.turn.CLAUDE_DOOR
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.head.OtherAnswer
import splice.head.OtherAsk
import splice.head.OtherHeads
import splice.share.RefusalVoice
import splice.share.ShareRelay

internal class ClaudeHeadLane(private val topology: Topology) {
    private val tiers = AnthropicTiers()
    private val toHead = DoorRelay(WallClock(System::currentTimeMillis))

    /** The door's heads once [open] has run; null before, when every request stays on the head. */
    @Volatile
    private var opened: Opened? = null

    /** From now on a model of [heads]' named on the head is relayed to them through [relay]. */
    fun open(relay: ShareRelay, heads: DesktopHeads) {
        opened = Opened(relay, heads)
    }

    /** What the head keyed [head] hands to other heads: the door's models when its command is `claude`, and nothing
     *  for any other head. */
    fun forHead(head: String): OtherHeads {
        if (head !in topology.resolveHeadKeys(CLAUDE_COMMAND)) return OtherHeads { null }
        val own = topology.heads[head]?.discoveryPrefix.orEmpty()
        return OtherHeads { ask ->
            val door = opened?.takeIf { prefixOf(ask.model, own) != null }
            door?.let { OtherAnswer { call -> it.answer(call, ask) } }
        }
    }

    /** The head whose selectors or readable names [model] starts as, other than the one whose prefix is [own]; null
     *  for an Anthropic ID or any other name, which stays on the head. */
    private fun prefixOf(model: String, own: String): String? {
        if (tiers.tierOf(model) != null) return null
        return topology.heads.entries
            .filter { (_, head) ->
                head.discoveryPrefix.let { it.isNotEmpty() && it != own && model.startsWith(it) } ||
                    readablePrefixes(head).any { it.isNotEmpty() && model.startsWith(it) }
            }
            .maxByOrNull { (_, head) -> head.discoveryPrefix.length }?.key
    }

    /** The prefixes [head]'s readable names start with: the one splice.toml gives it, and the one ClientModelNames
     *  makes from its selectors' prefix when that names another lab. */
    private fun readablePrefixes(head: HeadConfig): List<String> = listOfNotNull(
        head.claude.readable?.prefix,
        ClientModelNames.prefixOf(head.discoveryPrefix).takeIf { it != head.discoveryPrefix },
    )

    private inner class Opened(private val relay: ShareRelay, private val heads: DesktopHeads) {
        private val voice = RefusalVoice(CLAUDE_ERROR_PREFIX)

        /** [ask] relayed to the head that serves its model, or refused by name when none does. */
        suspend fun answer(call: ApplicationCall, ask: OtherAsk) {
            val lineup = heads.lineup()
            val (head, model) = lineup.find(ask.model)
                ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, notServed(lineup, ask.model))
            val caller = HeadLaneCaller(relay, held(model.model))
            val asked = DoorAsk(ask.request, ask.model, ask.session)
            toHead.send(call, caller, DoorRoute(head, model.upstream), asked, ask.counting)
        }

        /** Why [model] runs nowhere: its head is left out of the door, with the reason, or lists no such model. */
        private fun notServed(lineup: DesktopLineup, model: String): String {
            val key = prefixOf(model, own = "")
            val out = lineup.leftOut.firstOrNull { it.head == key }
            if (out != null) return "model \"$model\" does not run now, as ${out.command} is left out: ${out.reason}"
            val command = lineup.heads.firstOrNull { it.key == key }?.relay?.command ?: key
            return "model \"$model\" is no model of $command; `splice claude` lists the models claude runs"
        }
    }

    /** The window a session holds for [model]: the one Claude Code holds for a model it does not know. */
    private fun held(model: String): Long = if (model.endsWith(ONE_MILLION_SUFFIX, ignoreCase = true)) HELD_1M else HELD

    /** The head lane as a caller of the door's relay: no key opens the door to it, since its turns come through the
     *  head, and its sessions hold [window] for the model the turn names. */
    private class HeadLaneCaller(relay: ShareRelay, private val window: Long) :
        DoorCaller(RefusalVoice(CLAUDE_ERROR_PREFIX), relay, CLAUDE_DOOR) {
        override fun presents(header: String?): Boolean = false

        override fun closed(): String? = null

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
