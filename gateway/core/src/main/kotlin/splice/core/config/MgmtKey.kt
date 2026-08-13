// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ pre-public-port-baseline — invariant: 32 random bytes hex,
// 0600, minted once per key lifetime and cached in the state dir; the bearer for every /api call.
// Minted EAGERLY before the port opens (a dashboard load must never race an unminted key).
// timingSafe compare.
// SH-12 operator fork, decided: an unreadable-but-PRESENT key file mints-and-warns rather than
// failing daemon start. Reason: this is a single-user loopback daemon whose heads must come up —
// refusing to boot bricks every head over a mgmt-plane blip, while a LOUD rotation costs one
// dashboard re-auth and one line tells the operator exactly what happened and where the key is.
package splice.core.config

import splice.core.auth.bearerToken
import splice.core.util.DaemonLog
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom

public class MgmtKey(
    private val statePaths: StatePaths,
    private val log: (String) -> Unit = DaemonLog::write,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val value: String by lazy { ensure() }

    /** SH-12: non-null only when THIS process minted (fresh key). Doctor/status compare it to
     *  daemon uptime — a key minted minutes ago on an hours-old install is the rotated-bearer
     *  signature that used to surface only as unexplained 401s. */
    @Volatile
    public var mintedAtMs: Long? = null
        private set

    public fun get(): String = value

    private fun ensure(): String {
        val path = statePaths.mgmtKeyFile
        // SH-12: absent = first run, mint quietly. PRESENT but unreadable/blank = a permissions
        // change, a partial read, disk pressure — minting here revokes every existing bearer, so
        // it must be LOUD, never a silent fallthrough.
        var readFailure: String? = null
        if (Files.exists(path)) {
            try {
                val existing = Files.readString(path).trim()
                if (existing.isNotEmpty()) return existing
                readFailure = "present but blank"
            } catch (e: IOException) {
                readFailure = "unreadable (${e.javaClass.simpleName})"
            }
        }
        if (readFailure != null) {
            log(
                "[mgmt-key] $path $readFailure — minting a NEW key: every existing bearer " +
                    "(dashboard session, scripts, the launch shim's stop hook) is now invalid; " +
                    "re-copy the key from $path\n",
            )
        }
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        // Atomic 0600 write via the shared primitive (was an inline temp→chmod→move copy).
        SecureFile.writeAtomic0600(path, "$key\n")
        mintedAtMs = clock()
        return key
    }

    /** Constant-time bearer check (`Authorization: Bearer <key>`) — scheme parsing shared with
     *  HeadServer.authorize via [bearerToken] so the same token bytes work on both planes. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = bearerToken(header) ?: return false
        val a = presented.toByteArray()
        val b = value.toByteArray()
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
