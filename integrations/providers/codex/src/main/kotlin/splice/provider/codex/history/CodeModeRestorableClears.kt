package splice.provider.codex.history

import splice.provider.codex.CodeModeRecord
import splice.provider.codex.stream.batch.CodeModeBatchHistory

/** An unproved cleared callback is another branch, not a lost copy of an absent script's callbacks. */
internal object CodeModeRestorableClears {
    fun permits(record: CodeModeRecord, proof: CodeModeCallbackProof): Boolean =
        proof.candidates.all { it in proof.verified } || CodeModeBatchHistory.callbacks(record).isEmpty()
}
