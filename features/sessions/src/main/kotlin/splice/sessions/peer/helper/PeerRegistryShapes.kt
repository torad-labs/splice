// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2.2) — the shapes of a peer helper's two registry files,
// moved verbatim out of PeerRegistryRecord.kt: who the helper is, what it names the peer as, and the record and key file
// field by field. PeerRegistryRecord.kt writes them.
package splice.sessions.peer.helper

import kotlinx.serialization.Serializable
import splice.sessions.peer.codex.NativeProcess

/** The peer feature Splice's helpers announce. */
private const val PEER_FEATURE = "splice-peer-v1"

// why: the lifecycle binding layout Fence and the Temple read (Fence's lifecycle-owner-binding.md); 1 is the only one.
private const val LIFECYCLE_BINDING_VERSION = 1

/** What a helper's record says it is, in `kind` and `entrypoint`. */
internal const val PEER_KIND = "splice-peer"

/** Who a helper is, as a registry record spells it. */
public data class HelperIdentity(val pid: Long, val procStart: String?, val pidDomain: String?, val startedAt: Long)

/** What a record names the peer as: its registered name, session id and working directory, and for a codex peer the
 *  live Codex process that hosts its thread's lifecycle, which the record binds to the helper. */
public data class RecordPeer(
    val name: String,
    val sessionId: String,
    val cwd: String,
    val owner: NativeProcess? = null,
)

/** A process in a record's lifecycle binding, as Fence and the Temple read it. */
@Serializable
internal data class RegistryProcess(val pid: Long, val procStart: String, val pidDomain: String)

/** The record's `lifecycleBinding` (version 1): this session's lifecycle is hosted by [nativeOwner], and [peer], the
 *  helper the record is for, receives its messages. Fence checks both are still the processes named here. */
@Serializable
internal data class RegistryBinding(
    val version: Int = LIFECYCLE_BINDING_VERSION,
    val sessionId: String,
    val nativeOwner: RegistryProcess,
    val peer: RegistryProcess,
)

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
    val lifecycleBinding: RegistryBinding? = null,
)

@Serializable
internal data class RegistryKeyFile(val peerToken: String, val procStart: String? = null, val pidDomain: String? = null)
