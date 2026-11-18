// NEW: splice's own text on the openai-chat wire, in one collaborator every Chat Completions provider hands its
// placements to (`RequestPlacements by ChatPlacements()`): the compaction tail, the standing system prompt and a
// capability's text (docs/specs/harness.md, decision 5), each placed as its own seam says. Split out of
// OpenAiChatProvider (Nov 17, 2026), as ResponsesPlacements is out of ResponsesProvider.
package splice.dialect.chat.usertext

import splice.core.prompt.SystemPromptMode
import splice.dialect.chat.ChatSystemPrompt
import splice.upstream.BuiltTurn
import splice.upstream.RequestPlacements

/** Where splice's own text goes in a Chat Completions request. */
public class ChatPlacements : RequestPlacements {
    private val compactionTail = ChatCompactionTail()
    private val systemPrompt = ChatSystemPrompt()
    private val firstUserText = ChatFirstUserText()

    override fun withCompactionTail(turn: BuiltTurn, instructions: String): BuiltTurn =
        turn.copy(requestBody = compactionTail.append(turn.requestBody, instructions))

    override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn =
        turn.copy(requestBody = systemPrompt.apply(turn.requestBody, prompt, mode))

    /** A capability's text, at the end of the first user message. */
    override fun withFirstUserText(turn: BuiltTurn, text: String): BuiltTurn =
        turn.sending(firstUserText.append(turn.requestBody, text))
}
