// NEW: a canonical ancestor tail can place emission before the client's actual continuity echo.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.native.CodeModeAnchorCapture
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult

internal class CodeModeAncestorProseTest {
    @Test
    fun `a shifted ancestor echo is emitted once at the unchanged canonical boundary`() {
        val fixture = Fixture()
        val logical = fixture.logical()
        assertEquals(3, CodeModeHistoryIndex(fixture.raw, fixture.codec).boundary(fixture.b))
        assertEquals(
            1,
            logical.count { it == fixture.preface },
            "ancestor continuity must not survive as a second echo",
        )
        assertEquals(
            listOf(
                fixture.user,
                fixture.a.outer,
                fixture.codec.customOutput(fixture.a),
                fixture.preface,
                fixture.b.outer,
                fixture.codec.customOutput(fixture.b),
            ) + fixture.callbacks("c1"),
            logical,
        )
        val replay = fixture.projected().replayItems
        assertEquals(1, replay.sumOf { it.items.count { item -> item == fixture.reasoning } })
        assertEquals(3, replay.single().logicalOffset, "native continuity stays before B's preface")
    }

    @Test
    fun `an answered active script does not classify the surviving ancestor echo as steering`() {
        val fixture = Fixture()
        assertTrue(fixture.c.pending.all { it.clientId in fixture.c.results })
        assertEquals(6, fixture.codec.baselineBoundary(fixture.logical(), fixture.c))
        assertEquals(
            CodeModeExtra.NONE,
            fixture.history.extraContent(fixture.rewritten(), fixture.c),
            "ancestor commentary must not cut the answered active script",
        )
    }

    @Test
    fun `edited client prose is not removed as an ancestor continuity echo`() {
        val fixture = Fixture()
        val changed = ResponsesAssistantText.item("edited synthetic commentary", AssistantPhase.COMMENTARY)
        val input = fixture.raw.map { if (it == fixture.preface) changed else it }
        val rewritten = fixture.rewrite(input)
        val logical = fixture.projected(rewritten).logicalItems
        assertEquals(1, logical.count { it == fixture.preface })
        assertEquals(1, logical.count { it == changed })
        assertEquals(CodeModeExtra.STEERING, fixture.history.extraContent(rewritten, fixture.c))
    }

    private class Fixture {
        val codec = CodexCodeModeHistoryCodec(Json)
        val history = CodexCodeModeHistory(Json)
        val user = element("""{"role":"user","content":"synthetic user"}""")
        val preface = clientPreface()
        val reasoning = element(
            """{"type":"reasoning","id":"fixture-reasoning","encrypted_content":"fixture-opaque"}""",
        )
        val a = record("a", listOf(user), listOf("a1", "a2"))
        val b = record("b", listOf(user, a.outer, codec.customOutput(a)), listOf("b1"), listOf(a)).also {
            it.continuity = listOf(preface)
            it.continuityReplay = listOf(CodeModeNativeSegment(0, listOf(reasoning)))
        }
        val c = record(
            "c",
            listOf(user, a.outer, codec.customOutput(a), preface, b.outer, codec.customOutput(b)),
            listOf("c1"),
            listOf(a, b),
        ).also {
            it.phase = CodeModePhase.ACTIVE
            it.pending += CodeModePending("runtime-c1", "c1", "FixtureCallback", JsonObject(emptyMap()), true)
        }
        val raw = listOf(user) + callbacks("a1") + callbacks("a2") + preface + callbacks("b1") + callbacks("c1")

        fun callbacks(id: String): List<JsonElement> = listOf(
            element("""{"type":"function_call","call_id":"$id","name":"FixtureCallback","arguments":"{}"}"""),
            element("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
        )

        fun rewrite(input: List<JsonElement>): CodeModeBody {
            val result = history.canonicalize(body(input), listOf(a, b))
            assertEquals(emptyList<CodeModeOmission>(), result.omitted)
            return checkNotNull(result.body)
        }

        fun rewritten(): CodeModeBody = rewrite(raw)

        fun projected(body: CodeModeBody = rewritten()) =
            codec.conversation(codec.projection.project(checkNotNull(body.request).second)).body

        fun logical(): List<JsonElement> = projected().logicalItems

        private fun record(
            id: String,
            baseline: List<JsonElement>,
            calls: List<String>,
            completed: List<CodeModeRecord> = emptyList(),
        ): CodeModeRecord {
            val request = checkNotNull(body(baseline).request).second
            val input = checkNotNull(CodeModeAnchorCapture.inputBoundary(request, completed, codec))
            val outer = element(
                """{"type":"custom_tool_call","call_id":"outer-$id","name":"exec","input":"fixture source"}""",
            ).jsonObject
            return CodeModeRecord(
                id = "record-$id",
                key = "synthetic-key",
                outer = outer,
                outerCallId = "outer-$id",
                source = "fixture source",
                phase = CodeModePhase.COMPLETED,
                output = "fixture output",
                updatedAt = 0,
                lastDigest = "synthetic-request",
                baselineInputCount = input.fullCount,
                baselineInputDigest = "",
                metadataVersion = CODE_MODE_METADATA_VERSION,
                baselineLogicalCount = input.logicalCount,
                baselineLogicalDigest = "",
                nativeSegments = input.nativeSegments,
                continuity = emptyList(),
                continuityReplay = emptyList(),
            ).also { record ->
                record.replayAnchors = input.replayAnchors
                record.accepted.accept(calls.associateWith { CodeModeResult(it, "result") }, emptyMap())
                record.issued += CodeModeIssuedStep(
                    "synthetic-request",
                    calls.map { CodeModePending("runtime-$it", it, "FixtureCallback", JsonObject(emptyMap()), true) },
                )
            }
        }

        private fun body(items: List<JsonElement>): CodeModeBody {
            val preamble = element("""{"role":"developer","content":"synthetic preamble"}""")
            return CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(listOf(preamble) + items)))), Json)
        }

        private fun element(text: String): JsonElement = Json.parseToJsonElement(text)

        private fun clientPreface(): JsonObject {
            val parsed = AnthropicParse.parseAnthropicBody(
                """{"model":"claude-codex--fixture","max_tokens":100,"messages":[
                    {"role":"user","content":"synthetic user"},
                    {"role":"assistant","content":[
                        {"type":"text","text":"synthetic ancestor commentary"},
                        {"type":"tool_use","id":"b1","name":"FixtureCallback","input":{}}]}]}""",
            )
            val options = BuildOptions(
                compact = false,
                originalModel = "claude-codex--fixture",
                upstreamModel = "fixture",
                configEffort = "high",
                configSummary = null,
                showReasoning = ReasoningDisplay.OFF,
                replayReasoning = InjectPriorReasoning(false),
                decodeReasoningEnvelope = { null },
            )
            val builder = ResponsesRequestBuilder(CodexQuirks().defaultQuirks())
            val input = builder.build(parsed.typed, parsed.raw, options).req.getValue("input").jsonArray
            return input.single { codec.string(it as? JsonObject, "role") == "assistant" }.jsonObject
        }
    }
}
