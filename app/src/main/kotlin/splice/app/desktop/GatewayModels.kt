// NEW: head-catalog (docs/specs/door-default.md). The Splice gateway's answer to Claude Code's model discovery: every
// model the gateway routes, once, in `splice claude --json`'s order (DoorCatalog.picker, read at each request, so a
// sign-in, a sign-out or an expired login reaches the next listing with no restart), each labelled as that list labels
// it and described by the provider that serves it. The one list every session sees (Marcos, Nov 19, 2026: logins
// differ only in which models they serve, never in which models a session can see).
//
// The id of each row is one the gateway runs when a turn names it. A Claude model on the row the gateway's own routing
// of its bare Anthropic id lands on is listed by that bare id (`claude-opus-5-5`), so Claude Code folds it into the
// built-in alias row that resolves to it; a selector Claude Code would drop (no `claude` or `anthropic` in it, a
// hand-written discovery prefix) with `claude-` before it, which DesktopLineup.find reads back; every other row by its
// selector.
package splice.app.desktop

import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.catalog.DoorModel
import splice.client.desktop.DoorPickerRow
import splice.client.desktop.GatewayDiscovery
import splice.client.desktop.GatewayModel
import splice.client.models.AnthropicTiers

internal class GatewayModels {
    private val discovery = GatewayDiscovery()
    private val tiers = AnthropicTiers()

    /** The gateway's models in [lineup], in its picker's order, each labelled as the picker labels it. */
    fun of(lineup: DesktopLineup): List<GatewayModel> =
        DoorCatalog(lineup).picker().map { row -> GatewayModel(idOf(lineup, row), row.label, row.described) }

    /** The id the gateway lists [selector] by, which a launch hands Claude Code as the model it starts on: a selector
     *  spelled otherwise matches no row, and Claude Code then shows it as a row of its own beside the one the gateway
     *  lists (seen on 2.1.329, Nov 19, 2026: `claude--claude-opus-5-5` under the Opus row it folds into). [selector]
     *  itself when the lineup lists none. */
    fun idFor(lineup: DesktopLineup, selector: String): String =
        lineup.find(selector)?.second?.let { idOf(lineup, it) } ?: selector

    private fun idOf(lineup: DesktopLineup, row: DoorPickerRow): String =
        lineup.models.firstOrNull { it.model == row.model }?.let { idOf(lineup, it) } ?: discovery.idOf(row.model)

    private fun idOf(lineup: DesktopLineup, model: DoorModel): String {
        val named = tiers.modelOf(model.upstream)
        val served = named?.let { id -> lineup.models.firstOrNull { tiers.modelOf(it.upstream) == id } }
        return named?.takeIf { served == model } ?: discovery.idOf(model.model)
    }
}
