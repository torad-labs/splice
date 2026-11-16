// A key kept in one owner-only file in splice's state folder: 32 random bytes as hex after a prefix, minted when the
// file is missing, served from memory, replaced on purpose (file first, then memory) and checked by a constant-time
// bearer compare. The management key and Claude Desktop's desktop key are both built on it, so the SH-12 rule below
// holds for each.
//
// SH-12 operator fork, decided: an unreadable-but-PRESENT key file mints-and-warns rather than failing daemon start.
// Reason: this is a single-user loopback daemon whose heads must come up — refusing to boot bricks every head over a
// blip, while a LOUD rotation costs one re-auth and one line tells the operator exactly what happened and where the
// key is.
package splice.core.config

import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/** Runs before a key's quiet first mint, and throws to refuse it. */
public fun interface FirstMintGate {
    public operator fun invoke()
}

/**
 * The key in [file], [prefix] then 32 random bytes as hex. A mint over a file that is there but can't be used is said
 * in [log] under [tag], with [consequence]: what the new key refuses, and how to recover. [firstRun] may refuse the
 * quiet first mint.
 */
public class FileHeldKey(
    private val file: Path,
    private val prefix: String,
    private val tag: String,
    private val consequence: String,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val firstRun: FirstMintGate = FirstMintGate {},
) {
    private val initial: String by lazy { ensure() }

    /** The key [replace] minted, once one has: from then on the only key served and matched. */
    @Volatile
    private var replaced: String? = null

    /** Serializes the write of a replacement with its publication, so the key on disk and the key served are the
     *  same one whichever of two replacements lands last. */
    private val ioLock = Any()

    /** SH-12: non-null only when THIS process minted. Doctor/status compare it to daemon uptime — a key minted
     *  minutes ago on an hours-old install is the rotated-bearer signature that used to surface only as 401s. */
    @Volatile
    public var mintedAtMs: Long? = null
        private set

    public fun get(): String = replaced ?: initial

    /** A new key in place of the old one, which is refused from now on. File first, memory second: when the write
     *  fails nothing changed, and the next start serves whatever the file holds. */
    public fun replace(): String {
        val key = mint()
        synchronized(ioLock) {
            write(key)
            replaced = key
        }
        return key
    }

    /** Whether [header] presents this key as `Authorization: Bearer <key>`, compared in constant time. The scheme
     *  parsing is [BearerScheme.bearerToken], shared with every plane so the same token bytes work on each. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(Charsets.UTF_8) ?: return false
        val expected = get().toByteArray(Charsets.UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    private fun mint(): String {
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return prefix + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun write(key: String) {
        SecureFile.writeAtomic0600(file, "$key\n")
        mintedAtMs = clock()
    }

    private fun ensure(): String {
        // SH-12: only a genuine first run mints QUIETLY. A present-but-blank file, a permissions change, an
        // inaccessible parent dir, a dangling symlink — each revokes every existing bearer when we mint, so it must
        // be LOUD. The absence test is a DIRECT READ, never Files.exists as a pre-gate: NoSuchFileException is the
        // only positive evidence of absence, and even it is ambiguous — a DANGLING symlink throws NoSuch while the
        // path entry still exists. So after a NoSuch we disambiguate with exists(NOFOLLOW): present => dangling
        // link, rotate loudly; truly absent => quiet mint. A bare/NOFOLLOW exists() PRE-gate can't do this: it reads
        // false for an untraversable parent too and would mint SILENTLY on a permissions blip (DR-56).
        // Cancellables, not a catch-net: cancellation propagates, every other read failure is classified below.
        val read = Cancellables.runCatchingCancellable { Files.readString(file).trim() }
        val failure = read.exceptionOrNull()
        val problem = when {
            failure == null && read.getOrThrow().isNotEmpty() -> return read.getOrThrow()
            failure == null -> "present but blank"
            failure !is NoSuchFileException -> "unreadable (${SafeFailureText.render(failure)})"
            Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> "dangling symlink (${SafeFailureText.render(failure)})"
            else -> null
        }
        if (problem == null) {
            firstRun()
        } else {
            // Publish-gated wording (DR-56 redo): this line fires BEFORE the write below, and on an untraversable
            // state dir that write FAILS, so the consequence is spelled conditionally and is true on both paths.
            log("[$tag] $file $problem; minting a NEW key: if the replacement publishes, $consequence\n")
        }
        return mint().also(::write)
    }
}

// why: 32 random bytes: a guess is never the key.
private const val KEY_BYTES = 32
