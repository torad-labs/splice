package splice.provider.codex.history

import splice.provider.codex.history.durable.CodeModeHistoryStorage
import splice.provider.codex.history.durable.CodeModeHistorySwept
import java.nio.file.Path

/** A head's history files as the disk sees them: which the head holds now, and the sweep of the ones it let go. */
internal class CodeModeHistoryKept(
    private val storage: CodeModeHistoryStorage?,
    private val ledger: CodeModeUpstreamLedger,
) {
    /** Removes the journals of conversations last used before [beforeMs] that no request holds now. */
    fun sweep(beforeMs: Long): CodeModeHistorySwept? = storage?.sweep(beforeMs, ledger.hot())

    /** The history files of the conversations held in memory now, which a Settings clear leaves on the disk. */
    fun inUse(): Set<Path> = storage?.files(ledger.hot()).orEmpty()
}
