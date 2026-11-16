// NEW: muse-tool-cache (2026-11-16). Which of a conversation's tools the model calls through the caller, on a head
// whose tool list never changes. Until then every tool a Muse conversation loaded moved the whole conversation out of
// Meta's prompt cache, to be paid for again in full, because the loaded tool joined the request's tool list and Meta
// caches by prefix with the tool list before the input.
package splice.dialect.responses.request

import splice.core.wire.ToolDefinition

/** The tools a request's history gives the model, beside the ones its tool list does. */
internal data class HistoryTools(
    /** Deferred tools of the head's own tool surface that a call in the transcript names: each is declared before its
     *  first call ([ResponsesInputTools.appendToolUse]). */
    val declareByName: Map<String, ToolDefinition> = emptyMap(),
    /** The tool list the conversation's first request sent, the caller included, on a head that writes a later tool
     *  into the history ([splice.dialect.responses.ResponsesToolChanges.THROUGH_CALLER]). Null on any
     *  other head. */
    val listed: List<ToolDefinition>? = null,
)

/**
 * One request's walk of the conversation, on a head that writes a tool into the history where it arrived: the tools
 * the tool list holds, and the ones written so far. A tool the list holds is called under its name. Any other is
 * called through the caller: one written since, one the list holds that came back with a schema of its own, and one
 * the conversation never held, which the model can name to the caller all the same.
 *
 * It follows from the conversation up to each point and never from the turn, so a call Claude Code sends back goes to
 * the backend in the same bytes on every later request.
 */
internal class ResponsesArrivedTools(listed: List<ToolDefinition>) {
    private val listed = listed.mapTo(hashSetOf()) { it.name }
    private val written = hashSetOf<String>()

    /** The conversation wrote the tool [name] here. */
    fun wrote(name: String) {
        written.add(name)
    }

    /** Whether the tool list declares a tool named [name], whatever schema the conversation gave it since. */
    fun lists(name: String): Boolean = name in listed

    /** Whether, from this point of the conversation, the model calls [name] under its own name. */
    fun listsCurrent(name: String): Boolean = name in listed && name !in written
}
