// NEW: the mint-once, 0600, constant-time-compare secret that MgmtKey was, lifted out so a SECOND
// secret can share it (the turn key, split from the management key for v0.4.0). The body is
// MgmtKey's ensure()/matchesBearer() moved verbatim; only the path, the log label and the
// consequence sentence became parameters, because those are the three things that differ between
// the two keys. The SH-12 / DR-56 reasoning below travels with the code it explains.
package splice.core.config

import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/** One owner-only secret in the state dir: [path] holds 32 random bytes as hex, minted on first
 *  read. [label] names it in the one loud line a rotation prints, and [consequence] says who that
 *  rotation strands — the part of the line an operator acts on. */
public class StateKey(
    private val path: Path,
    private val label: String,
    private val consequence: String,
    private val log: LogSink,
    private val clock: WallClock,
) {
    private val value: String by lazy { ensure() }

    /** SH-12: non-null only when THIS process minted (fresh key). */
    @Volatile
    public var mintedAtMs: Long? = null
        private set

    public fun get(): String = value

    private fun ensure(): String {
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
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> "dangling symlink (${SafeFailureText.render(failure)})"
            else -> null
        }
        if (readFailure != null) {
            // Publish-gated wording (DR-56 redo, codex): this line fires BEFORE the write below,
            // and on an untraversable state dir that write FAILS — the old key survives and "is
            // now invalid" was a false diagnostic. The consequence is spelled conditionally so the
            // line is true on both paths.
            log(
                // SAFE-RENDER-EXEMPT[2026-08-31]: readFailure is not a throwable but a String built by the when above, whose every throwable-bearing branch already renders through the sanitizer
                "[$label] $path $readFailure — minting a NEW key: if the replacement publishes, " +
                    "$consequence; re-copy the key from $path\n",
            )
        }
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        // Atomic 0600 write via the shared primitive (was an inline temp→chmod→move copy).
        SecureFile.writeAtomic0600(path, "$key\n")
        mintedAtMs = clock()
        return key
    }

    /** Constant-time check of a raw presented credential — [BearerScheme] parsing stays with the
     *  caller, which knows which header spellings its plane accepts. */
    public fun matches(presented: String?): Boolean {
        if (presented == null) return false
        val a = presented.toByteArray()
        val b = value.toByteArray()
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }

    /** Constant-time bearer check (`Authorization: Bearer <key>`). */
    public fun matchesBearer(header: String?): Boolean = matches(BearerScheme.bearerToken(header))
}

// Companion dissolved to file scope (Kotlin style law, 2026-08-16 — HD-M8), same name, same value.
private const val KEY_BYTES = 32
