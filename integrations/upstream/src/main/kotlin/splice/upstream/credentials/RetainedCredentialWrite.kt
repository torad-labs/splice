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
import java.util.concurrent.atomic.AtomicReference

/** The credential document as one login stands now, read where a provider used to read its file. */
public fun interface CredentialText {
    /** The document's text. Throws as `Files.readString` does, so a genuinely absent file still reads as absent. */
    public fun read(): String
}

/** How a credential file's state is read: its mtime, size and content digest. Throws as the file's own reads do. */
public fun interface CredentialFileState {
    public fun identity(path: Path): CredentialFileIdentity
}

/** A credential document whose write failed, kept and served in the file's place until it is written (codex, grok, kimi
 *  and Claude through [TokenRenewal]; Muse's minted key on its own). [state] reads the file's state; a seam so a test
 *  can land a write between a reader's look at the waiting document and its judgment of it. */
public class RetainedCredentialWrite(
    private val path: Path,
    private val tag: String,
    private val log: LogSink,
    private val state: CredentialFileState = CredentialFileState { file ->
        CredentialFileIdentity(
            Files.getLastModifiedTime(file).toMillis(),
            Files.size(file),
            CredentialFileDigest.of(file),
        )
    },
) : CredentialText {
    /** The document a failed write was for, and the file as it stood then (null: unknown). A file that no longer
     *  stands so wins. */
    private data class Retained(val document: String, val stood: CredentialFileIdentity?) {
        /** The document holds live tokens. */
        override fun toString(): String = "Retained(document=<redacted>, stood=$stood)"
    }

    /** Every change is a compare-and-set on the exact [Retained] it saw (Nov 10, 2026): a read that found the file
     *  changed, or a write that landed, clears only the document it judged, never one a failed write kept meanwhile.
     *  A plain field cleared unconditionally let a reader that judged an older document drop a renewed pair a
     *  concurrent failed write had just kept, though the endpoint had already spent the refresh token before it. */
    private val retained = AtomicReference<Retained?>(null)

    /** Whether a granted pair is waiting to be written. */
    public val waiting: Boolean get() = current() != null

    override fun read(): String = current()?.document ?: Files.readString(path)

    /** Writes [document] owner-only and atomically. A failure keeps it for [read] and [flush]; the failure is returned
     *  for the caller's line, never thrown. A write that lands releases the document it wrote, and the one that waited
     *  when it began, which it was built from or flushes; a document a failed write kept meanwhile still waits. */
    public fun write(document: String): Throwable? {
        val before = retained.get()
        // The file as it stands before this write, which a failed write leaves it as: taken first, so a peer's write
        // that lands between the failure and this read is never taken for the state the document stands over.
        val stood = Cancellables.runCatchingCancellable { identity() }.fold(
            onSuccess = { it },
            // The file's state could not be read: unknown, so only its removal drops the document.
            onFailure = { null },
        )
        return Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(path, document) }.fold(
            onSuccess = {
                retained.updateAndGet { now -> now.takeUnless { it === before || it?.document == document } }
                null
            },
            onFailure = { failure ->
                retained.set(Retained(document, stood))
                failure
            },
        )
    }

    /** Writes a waiting document again; quiet while the write keeps failing, one line when it lands. */
    public fun flush() {
        val waiting = current() ?: return
        if (write(waiting.document) == null) {
            log("[$tag] the renewed credential that could not be written earlier is written now")
        }
    }

    /** The line a failed write leaves: the renewed login is still served, from memory. */
    public fun failedLine(failure: Throwable): String =
        "[$tag] token renewed but the credential write failed (${SafeFailureText.render(failure)}); " +
            "serving the renewed token from memory and writing it again at the next use"

    /** The waiting document while the file still stands as it did when the write failed; otherwise none. */
    private fun current(): Retained? {
        while (true) {
            val waiting = retained.get() ?: return null
            val stands = Cancellables.runCatchingCancellable { identity() }.fold(
                onSuccess = { waiting.stood == null || it == waiting.stood },
                // A file that cannot be read now is no proof it changed, so the renewed pair is still served; a file
                // that is gone was signed out.
                onFailure = { it !is NoSuchFileException },
            )
            if (stands) return waiting
            // Only the document judged here is dropped: one a failed write kept since is judged on its own next pass.
            if (retained.compareAndSet(waiting, null)) {
                log(
                    "[$tag] the credential file changed while a renewed credential waited to be written; " +
                        "its login stands",
                )
                return null
            }
        }
    }

    private fun identity(): CredentialFileIdentity = state.identity(path)
}
