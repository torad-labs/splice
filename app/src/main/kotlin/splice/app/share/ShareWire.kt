// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). What a sharing machine says about
// its heads, and what a client sends to join. Both ends are this codebase, so one set of types serves the
// listener (ShareServer) and `splice remote` (RemoteCommand); snake_case on the wire like every splice payload.
// ShareHeads, which builds the description from the running heads, joined these types here on 2026-10-22
// (concentration): a file of its own thinned this package's median under its neighbours.
package splice.app.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.app.control.ManagedHead
import splice.core.GATEWAY_VERSION
import splice.core.topology.Topology

/** `GET /share/v1/info`: who this machine is and the heads it offers, as their pickers show them now. */
@Serializable
internal data class ShareInfo(
    val server: String,
    val version: String,
    val heads: List<SharedHead>,
)

/** One offered head: what a client needs to regenerate the same command against it. The pinned model is
 *  [models]' first row. */
@Serializable
internal data class SharedHead(
    val key: String,
    val command: String,
    val models: List<SharedModel>,
)

/** One picker row: the wire id, its label, its window in force now, and the Claude tiers that name it. */
@Serializable
internal data class SharedModel(
    val id: String,
    val label: String,
    @SerialName("context_window") val contextWindow: Long,
    val slots: List<String> = emptyList(),
)

/** The heads this machine offers, described from what each running head serves NOW: its live catalog (rows,
 *  windows in force, pinned model, the Claude tiers that name each row), not a re-read of splice.toml. A client
 *  regenerating the head gets the picker this machine's own sessions see, discovered models included. */
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

/** `POST /share/v1/join`: the joining machine's name, shown in `splice share status` and revoked by. */
@Serializable
internal data class ShareJoin(val client: String)

/** The join's answer: the client's own token, shown this once and stored only hashed on the server. */
@Serializable
internal data class ShareJoined(val server: String, val token: String)

/** Paths both ends spell. */
internal const val SHARE_INFO_PATH = "/share/v1/info"
internal const val SHARE_JOIN_PATH = "/share/v1/join"
internal const val SHARE_HEADS_PATH = "/share/v1/heads"
