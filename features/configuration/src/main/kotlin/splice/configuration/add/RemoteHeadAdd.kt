// NEW: LAN share (docs/specs/lan-share.md; Marcos, 2026-10-22). A head another of the person's machines offers,
// regenerated here by `splice remote`: the same command, models and tiers, written through AddCommand like every
// other add (the same refusals, checks, atomic save, link and restart). Since phase 2 its provider is a `splice-remote`
// one: anthropic-passthrough to the sharing machine's relay for that head, `auth.remote` naming the `[remotes]`
// machine whose token (in this machine's share store, never keys.toml) it presents, and `forward_client_identity`
// carrying the session id and wire knobs the sharing head needs. Turns run on the sharing machine's login; nothing is
// copied here.
package splice.configuration.add

import splice.core.model.ModelRates
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.util.EnvReader

/** One head as the sharing machine described it, with the local [key] and [command] it is written under. */
public data class RemoteHead(
    /** The sharing machine's `[remotes]` key, which the provider's `auth.remote` names. */
    public val remote: String,
    /** The head's key on the sharing machine. */
    public val remoteKey: String,
    public val key: String,
    public val command: String,
    /** The relay for this head: `<remote url>/share/v1/heads/<remoteKey>`. */
    public val baseUrl: String,
    /** Pinned model first, as the sharing machine pins it. */
    public val models: List<RemoteModel>,
    /** The vendor family of the sharing machine's provider for this head; null where it names none. */
    public val family: String? = null,
)

/** One of a shared head's picker rows: its wire id, label, window in force, the Claude tiers naming it and its card. */
public data class RemoteModel(
    public val id: String,
    public val label: String,
    public val contextWindow: Long,
    public val slots: List<String>,
    /** The context the sharing head holds back below [contextWindow] when it places the client's compaction, null
     *  when it holds none. This machine's head scales the relayed raw counts, so it needs the same reserve. */
    public val compactionReserve: Long? = null,
    /** The card the sharing head prices this row at, written on the row so turns here are priced the same. */
    public val rates: ModelRates? = null,
)

internal class RemoteHeadAdd(private val command: AddCommand) {
    /** AddCommand's answer: true when the head was saved and is reachable as printed. */
    suspend fun add(head: RemoteHead, env: EnvReader): Boolean = command.addDescribed(profile(head), env)

    private fun profile(head: RemoteHead): AddProfile = AddProfile(
        name = head.key,
        baseUrl = head.baseUrl,
        provider = AddProviderSpec(
            dialect = DialectWires.name(Dialect.ANTHROPIC_PASSTHROUGH),
            authKind = AuthKind.SpliceRemote.wire,
            extra = listOf(
                "# Relayed to '${head.remoteKey}' on ${head.remote}, which runs every turn on its own login.",
                "quirks = { forward_client_identity = true }",
            ),
            authRemote = head.remote,
            family = head.family,
        ),
        head = AddHeadSpec(key = head.key, command = head.command),
        labels = AddLabels(summary = "${head.remoteKey} on ${head.remote}", origin = "splice remote"),
        // A splice head leaves its pinned model out of /v1/models, so its list cannot decide the rows. Tiers go in a
        // model_slots table: the sharing head may put one model behind several tiers, which a models list refuses.
        policy = AddModelPolicy(listAuthoritative = false, tierTable = true),
        models = head.models.map {
            AddModel(
                it.id,
                it.label,
                it.contextWindow,
                slots = it.slots,
                rates = it.rates,
                compactionReserve = it.compactionReserve,
            )
        },
    )
}
