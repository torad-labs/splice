// NEW: Oct 11, 2026 — the transcript copies as Settings > Your data counts and clears them: every copy the resume
// rewrite kept before it moved a transcript onto another model, of any age.
package splice.app.control.kept

import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

internal class TranscriptCopiesKept(private val paths: StatePaths) : KeptStore {
    override fun held(): StoreHeld {
        val held = TranscriptOriginals(paths).heldBefore(Long.MAX_VALUE)
        return StoreHeld(held.files.toLong(), held.bytes, null)
    }

    override fun clear(): String? =
        Cancellables.runCatchingCancellable { TranscriptOriginals(paths).deleteBefore(Long.MAX_VALUE) }
            .exceptionOrNull()?.let { SafeFailureText.render(it) }
}
