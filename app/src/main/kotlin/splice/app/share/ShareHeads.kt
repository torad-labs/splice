// NEW: LAN share, phase 1 (docs/specs/lan-share.md). The heads this machine offers, described from what
// each running head serves NOW: its live catalog (rows, windows in force, pinned model, the Claude tiers
// that name each row), not a re-read of splice.toml. A client regenerating the head gets the picker this
// machine's own sessions see, discovered models included.
package splice.app.share

import splice.app.control.ManagedHead
import splice.core.GATEWAY_VERSION
import splice.core.topology.Topology

internal class ShareHeads(
    private val topology: Topology,
    private val running: Map<String, ManagedHead>,
    /** This machine's name as clients are told it. */
    val server: String,
) {
    private val offered: List<String> = topology.share.sharedHeads(topology).filter { it in running }

    fun keys(): List<String> = offered

    /** The loopback port of an offered head, or null for any other name. */
    fun port(key: String): Int? = key.takeIf { it in offered }?.let { topology.heads[it]?.port }

    fun info(): ShareInfo = ShareInfo(server, GATEWAY_VERSION, offered.mapNotNull(::described))

    private fun described(key: String): SharedHead? {
        val catalog = running[key]?.let { it.statusline.catalog }?.live() ?: return null
        val slots = catalog.client.tierModels.entries.groupBy({ it.value }, { it.key })
        val rows = catalog.models.map { row ->
            SharedModel(row.id, row.label.ifBlank { row.id }, row.contextWindow, slots[row.id].orEmpty().sorted())
        }
        val pinned = rows.firstOrNull { it.id == catalog.pinnedModel }
        val command = topology.heads[key]?.let { it.claude.command } ?: key
        return SharedHead(key, command, listOfNotNull(pinned) + rows.filterNot { it == pinned })
    }
}
