// NEW: audit, Nov 9, 2026 — a minted Muse key whose write failed is kept, as codex, grok and kimi keep a renewed pair.
//
// A turn mints a key because the stored one stopped working. When the write of the new key failed, the key was
// dropped, so the dead key stayed on disk and every turn after it minted again. Now the document the write was for is
// served in the file's place (RetainedCredentialWrite) and written again at the next lookup or mint; a file that
// changes underneath it, a re-login or a logout, wins.
package splice.provider.muse

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.credentials.CredentialLock
import splice.upstream.credentials.RetainedCredentialWrite
import java.nio.file.Path

internal class MuseKeptKey(private val authPath: Path, private val log: LogSink) {
    /** Where every reader of the credential file reads it, and where a minted key is written. */
    val retained: RetainedCredentialWrite = RetainedCredentialWrite(authPath, "muse-auth", log)

    /** Writes a kept key again, under the credential lock so it never lands over a peer's write. Quiet when none waits;
     *  a lock that cannot be taken leaves it waiting, still served. Inside the lock, call [retained]'s flush itself. */
    suspend fun flush() {
        if (!retained.waiting) return
        Cancellables.runCatchingCancellable { CredentialLock.withLock(authPath, log = log) { retained.flush() } }
            .onFailure {
                log("[muse-auth] credential lock unavailable (${SafeFailureText.render(it)}); the minted key waits")
            }
    }
}
