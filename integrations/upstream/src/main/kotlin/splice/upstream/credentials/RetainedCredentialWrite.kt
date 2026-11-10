// NEW: audit, Nov 9, 2026 — a granted token pair whose local write failed is kept, not thrown away.
//
// By the time a renewal writes, the token endpoint has already spent the old refresh token. The three rotating
// providers (codex, grok, kimi) answered a failed write with PersistFailed and dropped the granted pair, so one full
// disk or one briefly unwritable folder left the spent token on disk and killed the login until someone signed in
// again. This keeps the document the write was for, serves it to every reader of the credential file in its place,
// and writes it again at the next renewal or lookup. A file that changes underneath it (a re-login, a peer's own
// rotation, a logout) wins: the retained document is dropped, never written over the newer file.
package splice.upstream.credentials

import splice.core.auth.CredentialFileDigest
import splice.core.auth.CredentialFileIdentity
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The credential document as one login stands now, read where a provider used to read its file. */
public fun interface CredentialText {
    /** The document's text. Throws as `Files.readString` does, so a genuinely absent file still reads as absent. */
    public fun read(): String
}

internal class RetainedCredentialWrite(
    private val path: Path,
    private val tag: String,
    private val log: LogSink,
) : CredentialText {
    /** The document a failed write was for, and the file as it stood then (null: unknown). A file that no longer
     *  stands so wins. */
    private data class Retained(val document: String, val stood: CredentialFileIdentity?) {
        /** The document holds live tokens. */
        override fun toString(): String = "Retained(document=<redacted>, stood=$stood)"
    }

    @Volatile
    private var retained: Retained? = null

    /** Whether a granted pair is waiting to be written. */
    val waiting: Boolean get() = current() != null

    override fun read(): String = current()?.document ?: Files.readString(path)

    /** Writes [document] owner-only and atomically. A failure keeps it for [read] and [flush]; the failure is returned
     *  for the caller's line, never thrown. */
    fun write(document: String): Throwable? =
        Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(path, document) }.fold(
            onSuccess = {
                retained = null
                null
            },
            onFailure = { failure ->
                val stood = Cancellables.runCatchingCancellable { identity() }.fold(
                    onSuccess = { it },
                    // The file's state could not be read either: unknown, so only its removal drops the document.
                    onFailure = { null },
                )
                retained = Retained(document, stood)
                failure
            },
        )

    /** Writes a waiting document again; quiet while the write keeps failing, one line when it lands. */
    fun flush() {
        val waiting = current() ?: return
        if (write(waiting.document) == null) {
            log("[$tag] the renewed credential that could not be written earlier is written now")
        }
    }

    /** The line a failed write leaves: the renewed login is still served, from memory. */
    fun failedLine(failure: Throwable): String =
        "[$tag] token renewed but the credential write failed (${SafeFailureText.render(failure)}); " +
            "serving the renewed token from memory and writing it again at the next use"

    /** The waiting document while the file still stands as it did when the write failed; otherwise none. */
    private fun current(): Retained? {
        val waiting = retained ?: return null
        val stands = Cancellables.runCatchingCancellable { identity() }.fold(
            onSuccess = { waiting.stood == null || it == waiting.stood },
            // A file that cannot be read now is no proof it changed, so the renewed pair is still served; a file that
            // is gone was signed out.
            onFailure = { it !is NoSuchFileException },
        )
        if (stands) return waiting
        retained = null
        log("[$tag] the credential file changed while a renewed credential waited to be written; its login stands")
        return null
    }

    private fun identity(): CredentialFileIdentity = CredentialFileIdentity(
        Files.getLastModifiedTime(path).toMillis(),
        Files.size(path),
        CredentialFileDigest.of(path),
    )
}
