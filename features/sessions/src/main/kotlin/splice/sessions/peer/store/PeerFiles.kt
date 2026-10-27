// NEW: Oct 27, 2026: peer.json's shapes (docs/specs/peer-messaging.md 2.1), moved out of PeerStore.kt unchanged when the
// codex peer's thread and owner joined them, so the store's file holds the store and these hold what it writes.
package splice.sessions.peer.store

import kotlinx.serialization.Serializable

// why: peer.json's layout version (spec 2.1); a reader meeting a later one knows the file is not its shape.
private const val PEER_FILE_VERSION = 1

/** peer.json as it sits on disk (spec 2.1). */
@Serializable
internal data class PeerFile(
    val v: Int = PEER_FILE_VERSION,
    val name: String,
    val sessionId: String,
    val createdAt: Long,
    val cwd: String,
    val key: PeerFileKey,
    val scope: PeerFileScope,
    val webhook: PeerFileWebhook? = null,
    val codex: PeerFileCodex? = null,
)

/** A codex peer's thread (docs/peers.md): its id, its Codex home, and the Codex process that attached it, if any. */
@Serializable
internal data class PeerFileCodex(val thread: String, val home: String, val owner: PeerFileOwner? = null)

@Serializable
internal data class PeerFileOwner(val pid: Long, val procStart: String, val pidDomain: String)

@Serializable
internal data class PeerFileKey(val id: String, val sha256: String, val rotatedAt: Long)

@Serializable
internal data class PeerFileScope(val sendTo: List<String>)

@Serializable
internal data class PeerFileWebhook(
    val url: String,
    val enabled: Boolean,
    val secretFile: String,
    val bearerFile: String?,
)
