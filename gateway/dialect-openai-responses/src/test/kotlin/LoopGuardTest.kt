// NEW: LoopGuard — the identical-failed-call circuit breaker. The measured pathology (claudex
// 2026-07-26): the same Edit re-issued 89-101x against the harness staleness guard. Pinned: the
// directive arms at the 3rd identical failure, escalates at the 5th, resets on success or changed
// arguments, treats key order as irrelevant, and never arms on unmarked (polling/flaky) results.
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.LoopGuard
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder

private val CODEX = ResponsesQuirks(providerTag = "claudex")

private fun opts() = BuildOptions(
    compact = false,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    configEffort = null,
    configSummary = null,
    showReasoning = ReasoningDisplay.from("text"),
    replayReasoning = InjectPriorReasoning(false),
    includeEncryptedReasoning = RequestEncryptedReasoning(true),
    sessionId = null,
    decodeReasoningEnvelope = { data ->
        buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("decoded", JsonPrimitive(data))
        }
    },
)

class LoopGuardTest {

    private fun conversation(repeats: Int, input: String = """{"file_path":"/x"}""", idPrefix: String = "c"): String {
        val calls = StringBuilder()
        for (i in 1..repeats) {
            calls.append(
                """{"role":"assistant","content":[{"type":"tool_use","id":"$idPrefix$i","name":"Edit","input":$input}]},""",
            )
            calls.append(
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$idPrefix$i",""" +
                    """"content":"<tool_use_error>File has been modified since read. Read it again.</tool_use_error>"}]},""",
            )
        }
        return calls.toString().trimEnd(',')
    }

    private fun analyze(bodyJson: String) =
        LoopGuard.analyze(parseAnthropicBody(bodyJson).typed.messages)

    private fun body(tail: String) = """{
        "model":"claude-codex--gpt-5.6-sol","max_tokens":1024,"stream":true,
        "messages":[{"role":"user","content":"fix the file"},$tail]
    }"""

    @Test
    fun `two identical failures do not arm`() {
        assertTrue(analyze(body(conversation(2))).isEmpty())
    }

    @Test
    fun `third identical failure arms the directive on that result`() {
        val directives = analyze(body(conversation(3)))
        assertEquals(setOf("c3"), directives.keys)
        assertTrue(directives.getValue("c3").contains("loop-guard"))
        assertTrue(directives.getValue("c3").contains("3 times"))
    }

    @Test
    fun `fifth identical failure escalates to refusal`() {
        val directives = analyze(body(conversation(5)))
        assertTrue(directives.getValue("c5").contains("STOP"))
        assertTrue(directives.getValue("c3").contains("3 times"))
    }

    @Test
    fun `a success between failures resets the streak`() {
        val tail = conversation(2) + "," +
            """{"role":"assistant","content":[{"type":"tool_use","id":"ok1","name":"Edit","input":{"file_path":"/x"}}]},""" +
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"ok1","content":"The file has been updated successfully."}]},""" +
            conversation(2)
        assertTrue(analyze(body(tail)).isEmpty())
    }

    @Test
    fun `changed arguments start a separate streak`() {
        val tail = conversation(2, """{"file_path":"/a"}""") + "," + conversation(2, """{"file_path":"/b"}""")
        assertTrue(analyze(body(tail)).isEmpty())
    }

    @Test
    fun `argument key order is irrelevant to the streak`() {
        val tail = conversation(2, """{"file_path":"/x","replace_all":false}""") + "," +
            conversation(1, """{"replace_all":false,"file_path":"/x"}""", idPrefix = "r")
        val directives = analyze(body(tail))
        assertEquals(setOf("r1"), directives.keys)
    }

    @Test
    fun `unmarked results never arm (polling and flaky tools)`() {
        val calls = StringBuilder()
        for (i in 1..6) {
            calls.append(
                """{"role":"assistant","content":[""" +
                    """{"type":"tool_use","id":"p$i","name":"TaskOutput","input":{"task_id":"t"}}]},""",
            )
            calls.append(
                """{"role":"user","content":[""" +
                    """{"type":"tool_result","tool_use_id":"p$i","content":"<retrieval_status>timeout</retrieval_status>"}]},""",
            )
        }
        assertTrue(analyze(body(calls.toString().trimEnd(','))).isEmpty())
    }

    @Test
    fun `the directive rides the wire ahead of the error text`() {
        val parsed = parseAnthropicBody(body(conversation(3)))
        val built = ResponsesRequestBuilder(CODEX).build(
            parsed.typed,
            parsed.raw,
            opts(),
        )
        val outputs = built.req["input"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            .map { it["output"]!!.jsonPrimitive.content }
        assertEquals(3, outputs.size)
        assertFalse(outputs[0].contains("loop-guard"))
        assertFalse(outputs[1].contains("loop-guard"))
        assertTrue(outputs[2].startsWith("[splice loop-guard]"))
        assertTrue(outputs[2].contains("File has been modified since read."))
    }

    @Test
    fun `quirk off restores plain passthrough`() {
        val parsed = parseAnthropicBody(body(conversation(4)))
        val built = ResponsesRequestBuilder(CODEX.copy(loopGuard = false)).build(
            parsed.typed,
            parsed.raw,
            opts(),
        )
        val text = built.req["input"]!!.jsonArray.toString()
        assertFalse(text.contains("loop-guard"))
    }
}
