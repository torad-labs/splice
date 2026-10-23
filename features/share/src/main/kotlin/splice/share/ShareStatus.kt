// NEW: LAN share, phase 2, milestone 3 (docs/specs/lan-share.md). What a joined machine reads from this one besides
// its turns, so a head imported there behaves like one here: whether this machine and each offered head are up
// (`/share/v1/health`), each head's picker as it stands now (`.../roster`, a SharedHead) and its plan windows
// (`.../usage`, QuotaJson's shape, the one every surface reads). All three need a joined machine's token.
package splice.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.usage.QuotaSnapshot

/** `GET /share/v1/health`: this machine, since when its listener is up, and each offered head's state. */
@Serializable
internal data class ShareHealth(
    val server: String,
    val version: String,
    @SerialName("up_since") val upSince: String,
    val heads: List<ShareHeadState>,
)

/** One offered head: [running] is false while it is not serving, so a turn sent to it now would fail. */
@Serializable
internal data class ShareHeadState(val key: String, val running: Boolean)

/** The plan windows a running head holds now (HeadUsageSource.quota), by topology key; null for none. The daemon
 *  answers it from its managed heads, as it does [SharedCatalogs]. */
public fun interface SharedQuotas {
    public fun of(key: String): QuotaSnapshot?
}

internal const val SHARE_HEALTH_PATH: String = "/share/v1/health"
