// NEW: how far a record's script has run.
package splice.provider.codex

/** What the script is waiting on and how far it has run: its client calls still [pending], the [output] it has
 *  produced, the calls and rounds it has used, and when and under which request digest it last moved. */
internal data class CodeModeProgress(
    val pending: MutableList<CodeModePending> = mutableListOf(),
    var output: String? = null,
    var totalCalls: Int = 0,
    var rounds: Int = 0,
    var updatedAt: Long,
    var lastDigest: String,
)
