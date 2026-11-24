package splice.sessions.peer.store

import splice.sessions.peer.codex.NativeProcess
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

// why: 32 random bytes for the peer key (2.1), base64url without padding, which is 43 characters.
private const val PEER_KEY_BYTES = 32

// why: 32 random bytes as hex for the webhook HMAC secret (3.2).
private const val SECRET_BYTES = 32

internal const val KEY_PREFIX = "splp_"

/** Reads a fresh bearer without letting a stdio connection adopt a different peer registration. */
public class PeerOwnedKeys internal constructor(private val folder: PeerFolder, private val keys: PeerKeys) {
    /** Read the key first, then verify the same registration still owns its hash. */
    public fun read(name: String, sessionId: String, owner: NativeProcess): String? {
        val key = try {
            Files.readString(folder.keyFile(name)).trim()
        } catch (_: IOException) {
            return null
        }
        val file = folder.read(name) ?: return null
        val recorded = file.owner ?: file.codex?.owner
        val registeredOwner = recorded?.let { NativeProcess(it.pid, it.procStart, it.pidDomain) }
        val sameRegistration = file.sessionId == sessionId && registeredOwner == owner
        return key.takeIf { sameRegistration && keys.hash(key) == file.key.sha256 }
    }
}

/** Peer keys and webhook secrets: minted from [random], and a key known to the daemon only by its sha256. */
internal class PeerKeys(private val random: SecureRandom) {
    fun mint(): String = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(PEER_KEY_BYTES))

    fun secret(): String = HexFormat.of().formatHex(bytes(SECRET_BYTES))

    fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

    fun nextId(current: String): String = "k${(current.removePrefix("k").toIntOrNull() ?: 0) + 1}"

    private fun bytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}
