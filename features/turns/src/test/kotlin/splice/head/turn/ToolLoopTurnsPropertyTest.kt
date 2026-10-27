// Goal (fix 4 of the Oct 27 Responses fixes): the turn-state token the backend returns is echoed on every later
// request of the same Claude Code turn, from the person's prompt through each tool-result request to the final answer,
// and a new prompt starts without one. Codex keeps one token per turn, the first the backend returned (codex-rs
// core/src/client.rs: ModelClientSession's turn_state OnceLock), and never carries it into the next turn.
//
// The property, over generated sessions: several conversations (a main thread and its subagents, in one session or
// several) interleave prompts and tool results in any order, and the backend answers some requests with a token and
// some with none. Every request echoes exactly the first token its own Claude Code turn received before it, and nothing
// when its turn has received none yet. The model is the rule itself, kept per conversation.
package splice.head.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import kotlin.random.Random

private const val STATE_HEADER = "x-codex-turn-state"
private const val RUNS = 300
private const val STEPS = 60

class ToolLoopTurnsPropertyTest {

    /** One conversation as Claude Code holds it: its messages so far, and the token its current turn holds. */
    private class Conversation(val session: String, val key: String) {
        val messages = mutableListOf<String>()
        var turnToken: String? = null
        var calls = 0
        val started: Boolean get() = messages.isNotEmpty()

        fun prompt(text: String) {
            if (started) messages += """{"role":"assistant","content":[{"type":"text","text":"done"}]}"""
            messages += """{"role":"user","content":${quoted(text)}}"""
            turnToken = null
        }

        fun toolResult() {
            calls += 1
            val id = "toolu_${key}_$calls"
            messages += """{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"Read","input":{}}]}"""
            messages +=
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"ok $calls"}]}"""
        }

        private fun quoted(text: String) = "\"$text\""
    }

    private fun meta(conversation: Conversation) = TurnMeta(
        compact = false,
        route = TurnRoute(
            stream = true,
            originalModel = "claude-codex--m",
            upstreamModel = "m",
            clientMaxTokens = null,
        ),
        reasoning = TurnReasoning(ReasoningDisplay.TEXT, "high", null, null),
        scope = TurnScope(conversationKey = conversation.key, sessionId = conversation.session),
    )

    @Test
    fun `every request echoes the first token its own Claude Code turn received, and a new prompt starts empty`() {
        repeat(RUNS) { run ->
            val random = Random(run)
            val turns = ToolLoopTurns()
            val sessions = List(1 + random.nextInt(2)) { "session-$run-$it" }
            val conversations = List(1 + random.nextInt(4)) { Conversation(sessions.random(random), "conv-$run-$it") }
            var issued = 0
            repeat(STEPS) { step ->
                val conversation = conversations.random(random)
                // A tool result continues the turn; a prompt, and the first request of a conversation, starts one. The
                // same words may open two turns of one conversation, as a person can type the same thing twice.
                if (!conversation.started || random.nextInt(3) == 0) {
                    conversation.prompt("prompt ${random.nextInt(3)}")
                } else {
                    conversation.toolResult()
                }
                val body = """{"model":"m","stream":true,"messages":[${conversation.messages.joinToString(",")}]}"""
                val meta = meta(conversation)
                turns.join(meta, AnthropicParse.parseAnthropicBody(body))

                assertEquals(
                    conversation.turnToken,
                    meta.upstreamHeaders.snapshot()[STATE_HEADER],
                    "run $run step $step: the request carries its own turn's first token",
                )
                if (random.nextInt(4) != 0) {
                    issued += 1
                    val token = "token-$run-$issued"
                    meta.upstreamHeaders.capture(STATE_HEADER, token)
                    if (conversation.turnToken == null) conversation.turnToken = token
                }
            }
        }
    }
}
