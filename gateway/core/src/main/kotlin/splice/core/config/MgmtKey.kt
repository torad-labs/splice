// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ 4ca99f7 — invariant: 32 random bytes hex,
// 0600, minted ONCE and cached in the state dir; the bearer for every /api call. Minted EAGERLY
// before the port opens (a dashboard load must never race an unminted key). timingSafe compare.
package splice.core.config

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
        }
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        Files.createDirectories(path.parent)
        Files.writeString(path, "$key\n")
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
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
