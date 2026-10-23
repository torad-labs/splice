// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). What a sharing machine says about
// its heads, and what a client sends to join. Both ends are this codebase, so one set of types serves the
// listener (ShareServer) and `splice remote` (RemoteCommand); snake_case on the wire like every splice payload.
package splice.app.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
