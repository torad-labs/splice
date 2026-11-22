// NEW: a mode's hooks at the door's moments (modes/README.md, "Hooks"). A session-start hook places its context and
// chooses the model once, and keeps both; a request hook changes the body on its way upstream; a hook that refuses
// refuses the turn; one that fails lets it go on unless its manifest says closed.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.Capability
import splice.core.capability.CapabilityCaller
import splice.core.capability.CapabilityCalling
import splice.core.capability.CapabilityTool
import splice.harness.hook.HookAnswer
import splice.harness.hook.HookCall
import splice.harness.hook.HookMoment
import splice.harness.hook.HookRun
import splice.harness.hook.HookSpec
import splice.harness.hook.HookWorker
import splice.harness.hook.ModeHookPort
import java.nio.file.Path

class ModeHooksTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    /** A port whose only hook is [answer] at [moment]. */
    private fun port(
        moment: HookMoment,
        closed: Boolean = false,
        answer: (HookCall) -> HookAnswer,
    ) =
        ModeHookPort { _ ->
            listOf(
                HookSpec(
                    "demo",
                    moment,
                    0,
                    TIMEOUT_MS,
                    closed,
                    HookWorker.InProcess(HookRun(answer)),
                ),
            )
        }

    @Test
    fun `a session-start hook places its context once and keeps it on every later turn`() = runBlocking {
        var runs = 0
        val hooks = port(HookMoment.SESSION_START) {
            runs++
            HookAnswer.AddContext("from the demo mode")
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))
        rig.send(head, Turn("s1", later("Fix the bug", "go on")))

        assertEquals(1, runs, "the session-start hook ran once")
        rig.provider.forEach { body ->
            assertTrue(firstUserTexts(body).contains("from the demo mode"), body)
        }
    }

    @Test
    fun `a session-start hook chooses the model its session runs on`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START) { HookAnswer.ChooseModel("astra-small") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        val body = Json.parseToJsonElement(rig.provider.single()).jsonObject
        assertEquals("astra-small", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `a request hook changes the body on its way to the provider`() = runBlocking {
        val hooks = port(HookMoment.REQUEST) { call ->
            HookAnswer.Rewrite(JsonObject(call.body + ("system" to JsonPrimitive("from the request hook"))))
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        val body = Json.parseToJsonElement(rig.provider.single()).jsonObject
        assertEquals("from the request hook", body.getValue("system").jsonPrimitive.content)
    }

    @Test
    fun `a hook that refuses refuses the turn with its reason, and the provider gets nothing`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START, closed = true) { HookAnswer.Refuse("not today") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue(stream.contains("event: error"), stream)
        assertTrue(stream.contains("mode demo refused this turn: not today"), stream)
        assertEquals(emptyList<String>(), rig.provider)
    }

    @Test
    fun `a hook that throws fails open, and the turn goes on`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START) { error("the hook broke") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        assertEquals(1, rig.provider.size, "the turn reached the provider")
    }

    @Test
    fun `a hook that throws fails closed, and the turn is refused`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START, closed = true) { error("the hook broke") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue(stream.contains("event: error"), stream)
        assertTrue(stream.contains("mode demo could not run its session_start hook"), stream)
        assertEquals(emptyList<String>(), rig.provider)
    }

    @Test
    fun `before_tool_call rewrites tool input and after_tool_call replaces the tool result`() = runBlocking {
        val advisor = advisorCap()
        val hooks = ModeHookPort { _ ->
            listOf(
                HookSpec(
                    "demo",
                    HookMoment.BEFORE_TOOL_CALL,
                    0,
                    TIMEOUT_MS,
                    false,
                    HookWorker.InProcess(
                        HookRun {
                            HookAnswer.Rewrite(buildJsonObject { put("question", "rewritten question") })
                        },
                    ),
                ),
                HookSpec(
                    "demo",
                    HookMoment.AFTER_TOOL_CALL,
                    0,
                    TIMEOUT_MS,
                    false,
                    HookWorker.InProcess(HookRun { HookAnswer.Replace("replaced result") }),
                ),
            )
        }
        val head = rig.head(listOf(advisor), workers = RigWorkers(hooks = hooks))
        rig.messages = { body ->
            if ("tool_result" in body) textStream("done") else toolStream("advisor", """{"question":"orig"}""")
        }

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue("done" in stream, stream)
        assertEquals(
            buildJsonObject { put("question", "rewritten question") },
            rig.asked.single().fields.getValue("input"),
        )
        val second = Json.parseToJsonElement(rig.provider.last()).jsonObject.getValue("messages").jsonArray
        val toolResult = second.last().jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("replaced result", toolResult.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `response hook rewrites the model answer before the client sees it`() = runBlocking {
        val hooks = port(HookMoment.RESPONSE) {
            HookAnswer.Rewrite(
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "rewritten answer")
                                },
                            )
                        },
                    )
                },
            )
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))
        rig.messages = { textStream("original answer") }

        val stream = rig.send(head, Turn("s1", opening("Say hi")))

        assertTrue("rewritten answer" in stream, stream)
        assertFalse("original answer" in stream, stream)
    }

    @Test
    fun `on_stop replace swaps the final reply and continue sends a follow-up round`() = runBlocking {
        var rounds = 0
        val hooks = port(HookMoment.ON_STOP) {
            rounds++
            if (rounds == 1) HookAnswer.Continue("Please verify.") else HookAnswer.Replace("verified reply")
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))
        rig.messages = { body -> if ("Please verify." in body) textStream("second") else textStream("first") }

        val stream = rig.send(head, Turn("s1", opening("Do work")))

        assertEquals(2, rig.provider.size, "on_stop Continue must trigger a second provider round")
        assertTrue("Please verify." in rig.provider.last(), rig.provider.last())
        assertTrue("verified reply" in stream, stream)
        assertFalse("first" in stream, stream)
    }

    private fun advisorCap(): Capability = rig.capability("advisor").copy(
        call = CapabilityCalling(
            CapabilityCaller.AGENT,
            tool = CapabilityTool(
                "advisor",
                "Ask the advisor.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("question") { put("type", "string") } }
                },
            ),
        ),
    )

    private fun textStream(text: String): String {
        val start = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        val delta = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""
        val stop = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
        return listOf(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""",
            "content_block_start" to start,
            "content_block_delta" to delta,
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to stop,
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (n, d) -> "event: $n\ndata: $d\n\n" }
    }

    private fun toolStream(name: String, inputJson: String): String {
        val toolBlock = """{"type":"tool_use","id":"toolu_1","name":"$name","input":{}}"""
        val start = """{"type":"content_block_start","index":0,"content_block":$toolBlock}"""
        val inputDelta = """{"type":"input_json_delta","partial_json":${JsonPrimitive(inputJson)}}"""
        val delta = """{"type":"content_block_delta","index":0,"delta":$inputDelta}"""
        val stop = """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}"""
        return listOf(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""",
            "content_block_start" to start,
            "content_block_delta" to delta,
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to stop,
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (n, d) -> "event: $n\ndata: $d\n\n" }
    }
}

private const val TIMEOUT_MS = 5_000L
