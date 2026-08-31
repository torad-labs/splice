// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ pre-public-port-baseline — invariant: 32 random bytes hex,
// 0600, minted once per key lifetime and cached in the state dir; the bearer for every /api call.
// Minted EAGERLY before the port opens (a dashboard load must never race an unminted key).
// timingSafe compare.
// SH-12 operator fork, decided: an unreadable-but-PRESENT key file mints-and-warns rather than
// failing daemon start. Reason: this is a single-user loopback daemon whose heads must come up —
// refusing to boot bricks every head over a mgmt-plane blip, while a LOUD rotation costs one
// dashboard re-auth and one line tells the operator exactly what happened and where the key is.
package splice.core.config

import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom

public class MgmtKey(
    private val statePaths: StatePaths,
    private val log: LogSink = LogSink(DaemonLog::write),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
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
        // SH-12: only a genuine first run mints QUIETLY. A present-but-blank file, a permissions
        // change, an inaccessible parent dir, a dangling symlink — each revokes every existing bearer
        // when we mint, so it must be LOUD. The absence test is a DIRECT READ, never Files.exists as
        // a pre-gate: NoSuchFileException is the only positive evidence of absence, and even it is
        // ambiguous — a DANGLING symlink throws NoSuch while the path entry still exists. So after a
        // NoSuch we disambiguate with exists(NOFOLLOW): present => dangling link, unreadable-PRESENT,
        // rotate loudly; truly absent => quiet mint. A bare/NOFOLLOW exists() PRE-gate can't do this:
        // it reads false for an untraversable parent too and would mint SILENTLY on a permissions
        // blip — the exact SH-12 bug (DR-56). Cancellables, not a catch-net: cancellation propagates,
        // every other read failure is classified below.
        val read = Cancellables.runCatchingCancellable { Files.readString(path).trim() }
        val failure = read.exceptionOrNull()
        val readFailure = when {
            failure == null && read.getOrThrow().isNotEmpty() -> return read.getOrThrow()
            failure == null -> "present but blank"
            failure !is java.nio.file.NoSuchFileException -> "unreadable (${SafeFailureText.render(failure)})"
            // A read that vanished: genuine absence, OR a dangling symlink (entry present, target
            // gone). Only the former is the quiet first run.
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> "dangling symlink ($failure)"
            else -> null
        }
        if (readFailure != null) {
            // Publish-gated wording (DR-56 redo, codex): this line fires BEFORE the write below,
            // and on an untraversable state dir that write FAILS — the old key survives and "is
            // now invalid" was a false diagnostic. The consequence is spelled conditionally so the
            // line is true on both paths.
            log(
                "[mgmt-key] $path $readFailure — minting a NEW key: if the replacement publishes, " +
                    "every existing bearer (dashboard session, scripts, the launch shim's stop " +
                    "hook) becomes invalid; re-copy the key from $path\n",
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
     *  HeadServer.authorize via [BearerScheme.bearerToken] so the same token bytes work on both planes. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header) ?: return false
        val a = presented.toByteArray()
        val b = value.toByteArray()
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }
}

// Companion dissolved to file scope (Kotlin style law, 2026-08-16 — HD-M8), same name, same value.
private const val KEY_BYTES = 32
