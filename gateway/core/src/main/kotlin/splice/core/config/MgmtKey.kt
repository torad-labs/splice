// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ pre-public-port-baseline — invariant: 32 random bytes hex,
// 0600, minted ONCE and cached in the state dir; the bearer for every /api call. Minted EAGERLY
// before the port opens (a dashboard load must never race an unminted key). timingSafe compare.
package splice.core.config

import splice.core.util.SecureFile
import splice.core.util.discard
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom

public class MgmtKey(private val statePaths: StatePaths) {
    private val value: String by lazy { ensure() }

    public fun get(): String = value

    private fun ensure(): String {
        val path = statePaths.mgmtKeyFile
        runCatching {
            if (Files.exists(path)) {
                val existing = Files.readString(path).trim()
                if (existing.isNotEmpty()) return existing
            }
        }.discard("unreadable/empty key file falls through to regeneration below")
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        // Atomic 0600 write via the shared primitive (was an inline temp→chmod→move copy).
        SecureFile.writeAtomic0600(path, "$key\n")
        return key
    }

    /** Constant-time bearer check (`Authorization: Bearer <key>`). */
    public fun matchesBearer(header: String?): Boolean {
        val presented = Regex("^Bearer\\s+(.+)$").find(header.orEmpty().trim())?.groupValues?.get(1)?.trim()
            ?: return false
        val a = presented.toByteArray()
        val b = value.toByteArray()
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
