// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2.2) — the shapes of a peer helper's two registry files,
// moved verbatim out of PeerRegistryRecord.kt: who the helper is, what it names the peer as, and the record and key file
// field by field. PeerRegistryRecord.kt writes them.
package splice.sessions.peer.helper

import kotlinx.serialization.Serializable

/** The peer feature Splice's helpers announce. */
private const val PEER_FEATURE = "splice-peer-v1"

/** What a helper's record says it is, in `kind` and `entrypoint`. */
internal const val PEER_KIND = "splice-peer"

/** Who a helper is, as a registry record spells it. */
public data class HelperIdentity(val pid: Long, val procStart: String?, val pidDomain: String?, val startedAt: Long)

/** What a record names the peer as: its registered name, session id and working directory. */
public data class RecordPeer(val name: String, val sessionId: String, val cwd: String)

/** The registry record, Claude Code's registry file field by field. */
@Serializable
internal data class RegistryRecordFile(
    val pid: Long,
    val sessionId: String,
    val cwd: String,
    val startedAt: Long,
    val procStart: String? = null,
    val version: String,
    val peerProtocol: Int = 1,
    val peerFeatures: List<String> = listOf(PEER_FEATURE),
    val kind: String = PEER_KIND,
    val entrypoint: String = PEER_KIND,
    val pidDomain: String? = null,
    val messagingSocketPath: String,
    val name: String,
    val nameSource: String = "user",
    val nameSince: Long,
    val status: String = "idle",
    val updatedAt: Long,
    val statusUpdatedAt: Long,
)

@Serializable
internal data class RegistryKeyFile(val peerToken: String, val procStart: String? = null, val pidDomain: String? = null)
