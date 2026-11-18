// NEW: splice's own text on the responses wire, in one collaborator: the compaction tail, the standing system prompt
// and a capability's text, each placed as its own seam says (split out of ResponsesProvider, Nov 17, 2026).
package splice.dialect.responses.usertext

import splice.core.prompt.SystemPromptMode
import splice.dialect.responses.request.ResponsesSystemPrompt
import splice.upstream.BuiltTurn
import splice.upstream.RequestPlacements

/** Where splice's own text goes in a Responses request. */
internal class ResponsesPlacements : RequestPlacements {
    private val compactionTail = ResponsesCompactionTail()
    private val systemPrompt = ResponsesSystemPrompt()
    private val firstUserText = ResponsesFirstUserText()

    override fun withCompactionTail(turn: BuiltTurn, instructions: String): BuiltTurn =
        turn.copy(requestBody = compactionTail.append(turn.requestBody, instructions))

    override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn =
        turn.copy(requestBody = systemPrompt.apply(turn.requestBody, prompt, mode))

    /** A capability's text, as one more user item after the opening user items (docs/specs/harness.md, decision 5). */
    override fun withFirstUserText(turn: BuiltTurn, text: String): BuiltTurn =
        turn.sending(firstUserText.append(turn.requestBody, text))
}
