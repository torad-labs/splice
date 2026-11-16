// NEW: desktop-door (docs/specs/desktop-door.md, decision 6). The desktop key: the one key the settings file holds,
// which every command in a Code tab session can read, so it opens the door and nothing else. It is a proof, never a
// credential: the door checks it and sends the head its own turn key in its place. Kept in splice's state folder,
// owner-only, minted at first use, and replaced only by `splice desktop rotate-key`; `splice rotate-key` replaces the
// management key and never this one.
package splice.app.desktop

import splice.client.desktop.DESKTOP_KEY_PREFIX
import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

internal class DesktopKey(private val file: Path, private val log: LogSink) {
    @Volatile
    private var held: String? = null
    private val lock = Any()

    /** The key the door opens to, minted into its file the first time there is none. */
    fun get(): String = held ?: synchronized(lock) { held ?: (read() ?: mint()).also { held = it } }

    /** A new key in place of the old one, which is refused from now on. File first: when the write fails, nothing
     *  changed. */
    fun replace(): String = synchronized(lock) { mint().also { held = it } }

    /** Whether [header] presents this key as a bearer, compared in constant time. */
    fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(Charsets.UTF_8) ?: return false
        val expected = get().toByteArray(Charsets.UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    /** The key on disk, or null when there is none to read. A file that can't be read is said in the log, since the
     *  key minted in its place refuses the one the settings file holds until `splice desktop on` writes it again. */
    private fun read(): String? {
        val read = Cancellables.runCatchingCancellable { Files.readString(file).trim() }
        val failure = read.exceptionOrNull()
        if (failure != null && failure !is NoSuchFileException) {
            val why = SafeFailureText.render(failure)
            log("[desktop] the desktop key at $file is unreadable ($why); minting a new one\n")
        }
        return read.getOrElse { "" }.ifEmpty { null }
    }

    private fun mint(): String {
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = DESKTOP_KEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
        SecureFile.writeAtomic0600(file, "$key\n")
        return key
    }
}

// why: 32 random bytes, as the management key has: a guess is never the key.
private const val KEY_BYTES = 32
