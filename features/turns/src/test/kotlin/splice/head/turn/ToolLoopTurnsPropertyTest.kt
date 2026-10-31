// Goal (fix 4 of the Oct 27 Responses fixes): the turn-state token the backend returns is echoed on every later
// request of the same Claude Code turn, from the person's prompt through each tool-result request to the final answer,
// and a new prompt starts without one. Codex keeps one token per turn, the first the backend returned (codex-rs
// core/src/client.rs: ModelClientSession's turn_state OnceLock), and never carries it into the next turn.
//
// The property, over generated sessions: several conversations (a main thread and its subagents, in one session or
// several) interleave prompts and tool results in any order, and the backend answers some requests with a token and
// some with none. Every request echoes exactly the first token its own Claude Code turn received before it, and nothing
// when its turn has received none yet. The model is the rule itself, kept per conversation.
//
// A prompt is the person's words, wherever Claude Code puts them. It sends the text a person types while a tool runs in
// the same user message as that tool's result (claudex's trace, Oct 27 08:08 to 08:21 CT: 29 distinct user messages
// carried a tool result and then a text block, 10 of them with one result and text of 20 characters or less), and it
// files its own notes for the model — system reminders — as text blocks too, which are never a message
// (integrations/claude-code ClientEcho.kt:8). So text beside a tool result opens a turn and a reminder beside one does
// not. A subagent is told apart by the agent id Claude Code heads its requests with (the same trace, Oct 27 05:44 to
// 08:26 CT: on every request of all 13 subagents, on no main thread's), because two subagents of one session whose
// first messages are identical share a conversation key by design (TurnScope.conversationKey).
package splice.head.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

/** More conversations than the 1,024 open turns the first cut of this rule kept, so a turn left open while they join
 *  would be the one dropped. */
private const val CROWD = 1_200

class ToolLoopTurnsPropertyTest {

    /** One conversation as Claude Code holds it: its messages so far, and the token its current turn holds. [agent] is
     *  the agent id its requests carry, null for a main thread. */
    private class Conversation(val session: String, val key: String, val agent: String? = null) {
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
            messages += call()
            messages += """{"role":"user","content":[${result()}]}"""
        }

        /** The person types [text] while a tool runs: Claude Code sends it in that tool's result message. */
        fun promptAfterToolResult(text: String) {
            messages += call()
            messages += """{"role":"user","content":[${result()},{"type":"text","text":${quoted(text)}}]}"""
            turnToken = null
        }

        /** A tool result with the client's own note to the model beside it, which is not a prompt. */
        fun toolResultWithReminder() {
            messages += call()
            val note = "<system-reminder>a note the client adds for the model</system-reminder>"
            messages += """{"role":"user","content":[${result()},{"type":"text","text":${quoted(note)}}]}"""
        }

        private fun call(): String {
            calls += 1
            return """{"role":"assistant","content":[{"type":"tool_use","id":"${id()}","name":"Read","input":{}}]}"""
        }

        private fun result() = """{"type":"tool_result","tool_use_id":"${id()}","content":"ok $calls"}"""

        private fun id() = "toolu_${key}_$calls"

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

    /** One request of [conversation] as it stands, joined to the turn it continues. */
    private fun send(turns: ToolLoopTurns, conversation: Conversation): TurnMeta {
        val body = """{"model":"m","stream":true,"messages":[${conversation.messages.joinToString(",")}]}"""
        val meta = meta(conversation)
        turns.join(meta, AnthropicParse.parseAnthropicBody(body), conversation.agent)
        return meta
    }

    /** What the request [send] sent carries upstream: the token of the turn it joined, or null. */
    private fun echoed(meta: TurnMeta): String? = meta.upstreamHeaders.snapshot()[STATE_HEADER]

    /** [conversation]'s next request, which the backend answers with [token]. Returns what the request echoed. */
    private fun sendAnswered(turns: ToolLoopTurns, conversation: Conversation, token: String): String? {
        val meta = send(turns, conversation)
        val carried = echoed(meta)
        meta.upstreamHeaders.capture(STATE_HEADER, token)
        if (conversation.turnToken == null) conversation.turnToken = token
        return carried
    }

    @Test
    fun `every request echoes the first token its own Claude Code turn received, and a new prompt starts empty`() {
        repeat(RUNS) { run ->
            val random = Random(run)
            val turns = ToolLoopTurns()
            val sessions = List(1 + random.nextInt(2)) { "session-$run-$it" }
            // Subagents of one session can open with identical words, so conversations share keys here: the agent id
            // a request carries is what tells them apart, and only the first conversation is a main thread.
            val conversations = List(1 + random.nextInt(4)) {
                val agent = if (it == 0) null else "agent-$run-$it"
                Conversation(sessions.random(random), "conv-$run-${random.nextInt(2)}", agent)
            }
            var issued = 0
            repeat(STEPS) { step ->
                val conversation = conversations.random(random)
                // A tool result continues the turn, and so does one the client files a note beside; a prompt, the
                // first request of a conversation, and text the person types while a tool runs each start one. The
                // same words may open two turns of one conversation, as a person can type the same thing twice.
                when {
                    !conversation.started || random.nextInt(3) == 0 ->
                        conversation.prompt("prompt ${random.nextInt(3)}")
                    random.nextInt(4) == 0 -> conversation.promptAfterToolResult("prompt ${random.nextInt(3)}")
                    random.nextInt(4) == 0 -> conversation.toolResultWithReminder()
                    else -> conversation.toolResult()
                }
                val meta = send(turns, conversation)

                assertEquals(
                    conversation.turnToken,
                    echoed(meta),
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

    @Test
    fun `text the person types beside a tool result starts a turn, and a reminder beside one does not`() {
        val turns = ToolLoopTurns()
        val conversation = Conversation("session-interrupted", "conv-interrupted")

        conversation.prompt("run the probe on both files")
        assertNull(sendAnswered(turns, conversation, "token-first-turn"), "the prompt opens a turn with no token")
        conversation.toolResult()
        assertEquals("token-first-turn", echoed(send(turns, conversation)), "the tool result continues that turn")
        conversation.toolResultWithReminder()
        assertEquals(
            "token-first-turn",
            echoed(send(turns, conversation)),
            "a note the client files beside a result is not a new prompt",
        )

        // The person types while the second tool runs: Claude Code sends the words in that tool's result message.
        conversation.promptAfterToolResult("stop and summarize instead")
        assertNull(sendAnswered(turns, conversation, "token-second-turn"), "the new prompt starts without a token")
        conversation.toolResult()
        assertEquals(
            "token-second-turn",
            echoed(send(turns, conversation)),
            "the next result carries the new turn's token",
        )
    }

    @Test
    fun `two subagents of one session whose first messages are identical each keep their own turn`() {
        val turns = ToolLoopTurns()
        val opening = "summarize the synthetic side task"
        val first = Conversation("session-subagents", "conv-identical", agent = "agent-first")
        val second = Conversation("session-subagents", "conv-identical", agent = "agent-second")

        first.prompt(opening)
        assertNull(sendAnswered(turns, first, "token-first-subagent"), "the first subagent's prompt starts bare")
        second.prompt(opening)
        assertNull(sendAnswered(turns, second, "token-second-subagent"), "the second subagent's prompt starts bare")

        first.toolResult()
        assertEquals("token-first-subagent", echoed(send(turns, first)), "the first subagent keeps its own token")
        second.toolResult()
        assertEquals("token-second-subagent", echoed(send(turns, second)), "the second subagent keeps its own token")
    }

    @Test
    fun `a turn left open while a crowd of other conversations joins still echoes its token`() {
        val turns = ToolLoopTurns()
        val waiting = Conversation("session-waiting", "conv-waiting")

        waiting.prompt("run the long probe")
        assertNull(sendAnswered(turns, waiting, "token-waiting-turn"), "the prompt opens the turn")

        repeat(CROWD) { n ->
            val other = Conversation("session-crowd-$n", "conv-crowd-$n")
            other.prompt("another person's prompt $n")
            sendAnswered(turns, other, "token-crowd-$n")
        }

        waiting.toolResult()
        assertEquals(
            "token-waiting-turn",
            echoed(send(turns, waiting)),
            "the waiting turn still carries its own token",
        )
    }
}
