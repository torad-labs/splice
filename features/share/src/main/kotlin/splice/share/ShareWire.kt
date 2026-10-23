// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). What a sharing machine says about
// its heads, and what a client sends to join. Both ends are this codebase, so one set of types serves the
// listener (ShareServer) and `splice remote` (RemoteCommand); snake_case on the wire like every splice payload.
// ShareHeads, which builds the description from the running heads, joined these types here on 2026-10-22
// (concentration): a file of its own thinned this package's median under its neighbours.
package splice.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.GATEWAY_VERSION
import splice.core.model.CompactionBudgets
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.topology.ProviderFamilyRule
import splice.core.topology.Topology

/** `GET /share/v1/info`: who this machine is and the heads it offers, as their pickers show them now. */
@Serializable
public data class ShareInfo(
    public val server: String,
    public val version: String,
    public val heads: List<SharedHead>,
)

/** One offered head: what a client needs to regenerate the same command against it. The pinned model is
 *  [models]' first row. */
@Serializable
public data class SharedHead(
    public val key: String,
    public val command: String,
    public val models: List<SharedModel>,
    /** The vendor family of the head's provider here (ProviderFamilyRule): what the client's console colours the
     *  regenerated head by, since its own provider only relays. Null where this machine names none. */
    public val family: String? = null,
)

/** One picker row: the wire id, its label, its window in force now (an `extra_windows` entry over the row's own),
 *  the Claude tiers that name it, and the card it is priced at. */
@Serializable
public data class SharedModel(
    public val id: String,
    public val label: String,
    @SerialName("context_window") public val contextWindow: Long,
    public val slots: List<String> = emptyList(),
    /** The context this head holds back below [contextWindow] when it places the client's compaction (a row
     *  override or the provider's calibration), null for none. The client's head scales the relayed counts, which
     *  arrive raw, so it mirrors this as the row's `compaction_reserve_tokens`. */
    @SerialName("compaction_reserve_tokens") public val compactionReserve: Long? = null,
    /** The card this head prices the row at, its own or the one its vendor publishes (TurnPrice), null for none.
     *  The client's head reaches the vendor only through this machine, so no published card is keyed on its URL:
     *  it mirrors this one onto the row, and its statusline and accounting price the turns as this machine does.
     *  A card's peak hours do not travel, as splice.toml has no spelling for them. */
    public val rates: ModelRates? = null,
)

/** The heads this machine offers, described from what each running head serves NOW: its live catalog (rows,
 *  windows in force, pinned model, the Claude tiers that name each row), not a re-read of splice.toml. A client
 *  regenerating the head gets the picker this machine's own sessions see, discovered models included. */
internal class ShareHeads(
    private val topology: Topology,
    private val running: SharedCatalogs,
    /** This machine's name as clients are told it. */
    val server: String,
) {
    private val offered: List<String> = topology.share.sharedHeads(topology).filter { running.of(it) != null }

    fun keys(): List<String> = offered

    /** The loopback port of an offered head, or null for any other name. */
    fun port(key: String): Int? = key.takeIf { it in offered }?.let { topology.heads[it]?.port }

    fun info(): ShareInfo = ShareInfo(server, GATEWAY_VERSION, offered.mapNotNull(::described))

    private fun described(key: String): SharedHead? {
        val served = running.of(key) ?: return null
        val catalog = served.live()
        val slots = catalog.client.tierModels.entries.groupBy({ it.value }, { it.key })
        val price = TurnPrice(served)
        val rows = catalog.models.map { row ->
            SharedModel(
                id = row.id,
                label = row.label.ifBlank { row.id },
                // The window this head scales and compacts by, which an extra_windows entry can set below the row's.
                contextWindow = catalog.contextWindowFor(row.id),
                slots = slots[row.id].orEmpty().sorted(),
                compactionReserve = CompactionBudgets.forRow(served, row.id)?.totalTokens,
                rates = price.cardOf(row.id),
            )
        }
        val pinned = rows.firstOrNull { it.id == catalog.pinnedModel }
        val head = topology.heads[key]
        val command = head?.let { it.claude.command } ?: key
        val family = head?.let { h -> topology.providers[h.provider]?.let { ProviderFamilyRule().of(h.provider, it) } }
        return SharedHead(key, command, listOfNotNull(pinned) + rows.filterNot { it == pinned }, family)
    }
}

/** The model catalog a head that started serves, by topology key; null for a head that is not running. The daemon
 *  answers it from its managed heads, so this module never sees how a head is assembled. */
public fun interface SharedCatalogs {
    public fun of(key: String): ModelCatalog?
}

/** `POST /share/v1/join`: the joining machine's name, shown in `splice share status` and revoked by. */
@Serializable
internal data class ShareJoin(val client: String)

/** The join's answer: the client's own token, shown this once and stored only hashed on the server. */
@Serializable
public data class ShareJoined(public val server: String, public val token: String)

/** Paths both ends spell. */
internal const val SHARE_INFO_PATH: String = "/share/v1/info"
internal const val SHARE_JOIN_PATH: String = "/share/v1/join"
internal const val SHARE_HEADS_PATH: String = "/share/v1/heads"
