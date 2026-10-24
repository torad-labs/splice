// NEW: where a script started in the client's own history, and whether a later history still starts with it.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputDigest
import splice.core.perf.InputItemDigests
import splice.provider.codex.CodeModeClientStart

/** The client's [conversation], preamble aside, read as the prefix each script started after. Each item is digested
 *  once through the request's [digests], and each prefix once however many scripts started after it. */
internal class CodeModeClientPrefix(private val conversation: List<JsonElement>, digests: InputItemDigests) {
    private val hexes by lazy { digests.hexItems(conversation) }
    private val prefixes = HashMap<Int, String>()

    /** Where a script started now: after all of [conversation]. */
    fun start(): CodeModeClientStart = CodeModeClientStart(conversation.size, digest(conversation.size))

    /** How many of [conversation]'s items the history [start] names is, or null when [conversation] does not start
     *  with it: the client cut or edited that history away. */
    fun at(start: CodeModeClientStart): Int? =
        start.count.takeIf { it in 0..conversation.size && digest(it) == start.digest }

    private fun digest(count: Int): String =
        prefixes.getOrPut(count) { InputDigest.hex(hexes.subList(0, count).joinToString("")) }
}
