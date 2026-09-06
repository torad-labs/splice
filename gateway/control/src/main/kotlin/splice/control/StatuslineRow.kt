// NEW: the picked model row as the statusline should show it. Claude Code fixes its context window
// per PROCESS (one constant, via CLAUDE_CODE_MAX_CONTEXT_TOKENS) and splice scales the token
// counts it reports so every row compacts at its own declared window, which leaves the blob
// Claude Code pipes to /statusline in client units: on a 500k row over a 256k session the bar read
// "…/256k" with counts x 0.512 however the operator switched (operator report, 2026-09-02). This
// lens undoes that scaling for the picked row and names it by its catalog label; with no catalog,
// or a row that already agrees with the client, every number passes through untouched.
package splice.control

import splice.core.model.ModelCatalog

internal class StatuslineRow(private val catalog: ModelCatalog?) {

    /** The picked model id when it is one of this head's declared rows, else null. */
    private fun declared(id: String?): String? = id?.takeIf { catalog?.contains(it) == true }

    /** The catalog label of a declared row; null leaves the blob's own display_name/id in charge. */
    fun label(id: String?): String? = declared(id)?.let { catalog?.labelFor(it) }

    /** (window, used) in the picked row's REAL units: the reported numbers unless the row is a
     *  scaled one, where the declared window replaces the client's and the count is divided by the
     *  same factor splice multiplied it by. `used_percentage` needs no repair: Claude Code computed
     *  it from the scaled counts against the client window, which is the same ratio. */
    fun window(id: String?, reportedSize: Long, reportedUsed: Long): Pair<Long, Long> {
        val row = declared(id)
        val scale = row?.let { catalog?.usageScale(it) } ?: 1.0
        return if (row == null || scale == 1.0) {
            reportedSize to reportedUsed
        } else {
            (catalog?.contextWindowFor(row) ?: reportedSize) to (reportedUsed / scale).toLong()
        }
    }
}
