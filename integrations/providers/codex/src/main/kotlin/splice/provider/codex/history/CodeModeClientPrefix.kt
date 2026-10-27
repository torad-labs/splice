// NEW: where a script started in the client's own history, and whether a later history still starts with it.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputDigest
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.provider.codex.CodeModeClientStart
import java.security.MessageDigest
import java.util.HexFormat

/** The client's [conversation], preamble aside, read as the prefix each script started after. Each item is digested
 *  once through the request's [digests], and each prefix once however many scripts started after it. One running
 *  digest goes forward through the items, so prefixes asked for in order cost one pass over them. */
internal class CodeModeClientPrefix(
    private val conversation: List<JsonElement>,
    digests: InputItemDigests,
    private val media: Map<String, List<JsonElement>> = emptyMap(),
) {
    private val hexes by lazy { digests.hexItems(conversation) }
    private val prefixes = HashMap<Int, String>()
    private val running = MessageDigest.getInstance(PREFIX_DIGEST)
    private var fed = 0
    private val projected by lazy { ResponsesCodeModeProjection().project(JsonArray(conversation)) }

    /** Where a script started now: after all of [conversation]. */
    fun start(): CodeModeClientStart = CodeModeClientStart(
        conversation.size,
        digest(conversation.size),
        CodeModeClientAcknowledgements.capture(conversation, media),
    )

    /** How many of [conversation]'s items the history [start] names is, or null when [conversation] does not start
     *  with it: the client cut or edited that history away. A tool result Claude Code changed in place, cleared or
     *  edited, leaves the history it stands in: the items [start] acknowledged still lead [conversation], each
     *  changed result standing for itself, and GPT's reasoning the client replays among them counts before the end. */
    fun at(start: CodeModeClientStart): Int? =
        start.count.takeIf { it in 0..conversation.size && digest(it) == start.digest } ?: changed(start)

    private fun changed(start: CodeModeClientStart): Int? {
        val boundary = start.acknowledgement?.boundary(projected) ?: return null
        return boundary + projected.replayItems.filter { it.logicalOffset < boundary }.sumOf { it.items.size }
    }

    /** [InputDigest.hex] of the first [count] item hexes joined, the digest a kept start holds. */
    private fun digest(count: Int): String = prefixes.getOrPut(count) {
        val joined = { InputDigest.hex(hexes.subList(0, count).joinToString("")) }
        if (count < fed) return@getOrPut joined()
        hexes.subList(fed, count).forEach { running.update(it.toByteArray(Charsets.UTF_8)) }
        fed = count
        val copy = running.clone() as? MessageDigest ?: return@getOrPut joined()
        HexFormat.of().formatHex(copy.digest())
    }
}

private const val PREFIX_DIGEST = "SHA-256"
