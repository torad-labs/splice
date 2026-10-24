// NEW: a self-served head gets its own earlier reasoning back (Marcos, 2026-10-23: "pass reasoning for self served
// heads like glm and qwen"). llama-server reads an assistant message's reasoning_content and the model's chat template
// renders it inside that turn's think block. Without it every earlier turn renders an empty think block, so the model
// reads a history in which it never reasoned, and GLM-5.3 repeats reasoning it already did. Splice's own wait notice
// rides the client's history as a thinking block too, and it is never the model's reasoning.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.SpliceNotice

private val REPLAYING = ChatQuirks(providerTag = "glml53", reasoning = ChatReasoningQuirks(replayReasoning = true))
private val HOSTED = ChatQuirks(providerTag = "xai")

private fun request(json: String, quirks: ChatQuirks): JsonObject =
    ChatRequestBuilder(quirks)
        .build(AnthropicParse.parseAnthropicBody(json).typed, upstreamModel = "m", compact = false)
        .req

private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }
private fun JsonObject.role() = this["role"]?.jsonPrimitive?.content
private fun JsonObject.text() = this["content"]?.jsonPrimitive?.content
private fun JsonObject.reasoning() = this["reasoning_content"]?.jsonPrimitive?.content

private const val ANSWERED = """{"model":"m","messages":[
    {"role":"user","content":"which file holds the config?"},
    {"role":"assistant","content":[
        {"type":"thinking","thinking":"The config is read at boot, so I look at the loader first.","signature":""},
        {"type":"text","text":"It is splice.toml."}
    ]},
    {"role":"user","content":"thanks"}
]}"""

class ChatReasoningReplayTest {

    @Test
    fun `an earlier answer carries the reasoning the model wrote before it`() {
        val answer = request(ANSWERED, REPLAYING).messages()[1]
        assertEquals("assistant", answer.role())
        assertEquals("It is splice.toml.", answer.text())
        assertEquals("The config is read at boot, so I look at the loader first.", answer.reasoning())
    }

    @Test
    fun `an earlier tool call carries the reasoning that chose it`() {
        val msgs = request(
            """{"model":"m","messages":[
                {"role":"user","content":"list the repo"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"I need the file list before anything else."},
                    {"type":"tool_use","id":"t1","name":"run","input":{"cmd":"ls"}}
                ]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"a.kt"}]}
            ]}""",
            REPLAYING,
        ).messages()
        val call = msgs[1]
        assertEquals("t1", call["tool_calls"]!!.jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("I need the file list before anything else.", call.reasoning())
        assertEquals(listOf("user", "assistant", "tool"), msgs.map { it.role() })
    }

    // The chat stream writes one reasoning run as consecutive thinking deltas; a turn whose reasoning reached the
    // client as two blocks is still the one reasoning the model wrote, in that order, with nothing added between.
    @Test
    fun `reasoning that reached the client as several blocks rides back whole and in order`() {
        val call = request(
            """{"model":"m","messages":[
                {"role":"user","content":"go"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"First the loader. "},
                    {"type":"text","text":"Looking."},
                    {"type":"thinking","thinking":"Then the parser."},
                    {"type":"tool_use","id":"t2","name":"read","input":{"path":"Parser.kt"}}
                ]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}
            ]}""",
            REPLAYING,
        ).messages()[1]
        assertEquals("First the loader. Then the parser.", call.reasoning())
        assertEquals("Looking.", call.text())
    }

    @Test
    fun `splice's wait notice is never sent as the model's reasoning`() {
        val req = request(
            """{"model":"m","messages":[
                {"role":"user","content":"go"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"[splice] holding this turn open.","signature":"${SpliceNotice.SIGNATURE}"},
                    {"type":"thinking","thinking":"The model's own thought."},
                    {"type":"text","text":"answer"}
                ]},
                {"role":"user","content":"next"}
            ]}""",
            REPLAYING,
        )
        assertEquals("The model's own thought.", req.messages()[1].reasoning())
        assertFalse(req.toString().contains("[splice] holding"), req.toString())
        assertFalse(req.toString().contains(SpliceNotice.SIGNATURE), req.toString())
    }

    // A turn cut off after it reasoned is still a turn the model took: it keeps its place between the two user
    // messages, so they are no longer merged into one.
    @Test
    fun `a turn that only reasoned keeps its place and its reasoning`() {
        val msgs = request(
            """{"model":"m","messages":[
                {"role":"user","content":"first"},
                {"role":"assistant","content":[{"type":"thinking","thinking":"half a thought"}]},
                {"role":"user","content":"second"}
            ]}""",
            REPLAYING,
        ).messages()
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.role() })
        assertEquals("half a thought", msgs[1].reasoning())
        assertEquals("", msgs[1].text())
    }

    @Test
    fun `a turn that held only a wait notice still maps to nothing`() {
        val msgs = request(
            """{"model":"m","messages":[
                {"role":"user","content":"first"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"[splice] holding this turn open.","signature":"${SpliceNotice.SIGNATURE}"}
                ]},
                {"role":"user","content":"second"}
            ]}""",
            REPLAYING,
        ).messages()
        assertEquals(listOf("user"), msgs.map { it.role() })
        assertEquals("first\n\nsecond", msgs.single().text())
    }

    // A missing reasoning stays missing: an empty field would tell the template the model reasoned nothing.
    @Test
    fun `an empty reasoning sends no field`() {
        val answer = request(
            """{"model":"m","messages":[
                {"role":"user","content":"q"},
                {"role":"assistant","content":[{"type":"thinking","thinking":""},{"type":"text","text":"a"}]},
                {"role":"user","content":"q2"}
            ]}""",
            REPLAYING,
        ).messages()[1]
        assertFalse(answer.containsKey("reasoning_content"), answer.toString())
        assertEquals("a", answer.text())
    }

    @Test
    fun `a hosted vendor's request carries no reasoning`() {
        val req = request(ANSWERED, HOSTED)
        assertFalse(req.toString().contains("reasoning_content"), req.toString())
        assertEquals("It is splice.toml.", req.messages()[1].text())
    }
}
