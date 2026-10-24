// The BYTE-IDENTITY WALL for the kimi passthrough: the request bytes kimi receives and the client-facing
// transcripts the translator produces, frozen against committed goldens. The unit tests around them assert
// PROPERTIES, so a rewrite that changes field ORDER, drops a verbatim-forwarded unknown field or re-nests
// output_config passes them all while changing what kimi receives, and with it prompt-cache stability.
//
// Kimi's bytes are an operator lock. If a change moves these bytes, that change is wrong. Regenerate ONLY
// when the operator has decided kimi's wire genuinely changes:
//   UPDATE_GOLDENS=true ./gradlew :integrations-dialects-anthropic:test --rerun-tasks
// then READ THE DIFF before committing it. (An env var, not a -D system property: the shared Test
// convention forwards neither, and test workers DO inherit the environment.)
//
// [KIMI_QUIRKS] below must always construct KIMI's deformation set. The .json/.txt files under
// resources/goldens/ never change.
package splice.dialect.anthropic

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path

/** KIMI's deformation set: every deformation knob this dialect can apply, as kimi runs them. */
private val KIMI_QUIRKS = KimiProfileFixture().kimi("kimi")

private val GOLDEN_DIR: Path = Path.of("src", "test", "resources", "goldens")
private val JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
private val UPDATING = System.getenv("UPDATE_GOLDENS") == "true"

/** The client-facing half of a failure: the wire error type and the provider-tagged message. The rest of
 *  [TurnOutcome.Failure] is splice's own bookkeeping, so freezing its whole `toString` would red this wall
 *  for a defaulted internal field while no byte the client sees had changed. */
private fun failureSubject(failure: TurnOutcome.Failure): String =
    "${failure.type.wireName} ${failure.message}"

private fun assertGolden(name: String, actual: String, subject: String = "what Kimi receives") {
    val file = GOLDEN_DIR.resolve(name)
    val body = if (actual.endsWith("\n")) actual else actual + "\n"
    if (UPDATING) {
        // Update mode rewrites the expectation, so under CI it would turn this wall into a
        // permanent green regardless of what the code does — the fake-green class the gate
        // discipline forbids. Refuse loudly instead of silently passing.
        check(System.getenv("CI") != "true") {
            "UPDATE_GOLDENS is set under CI: that would rewrite the wall instead of checking it. " +
                "Goldens are regenerated deliberately on a workstation and committed after reading the diff."
        }
        Files.createDirectories(GOLDEN_DIR)
        Files.writeString(file, body)
        return
    }
    assertTrue(Files.exists(file)) {
        "missing golden $name — regenerate with UPDATE_GOLDENS=true and READ THE DIFF"
    }
    assertEquals(Files.readString(file), body) {
        "GOLDEN MOVED ($name). Kimi behavior is frozen by the claude-head campaign; a diff here " +
            "means the change under test altered $subject. Fix the change, not the golden. If the " +
            "operator has decided it genuinely changes, regenerate with UPDATE_GOLDENS=true and " +
            "read the diff."
    }
}

private fun buildKimi(
    json: String,
    compact: Boolean = false,
    quirks: PassthroughQuirks = KIMI_QUIRKS,
): String {
    val built = PassthroughRequestBuilder(quirks).build(
        AnthropicParse.parseAnthropicBody(json),
        upstreamModel = "k3",
        originalModel = "claude-kimi--k3[1m]",
        compact = compact,
    )
    return JSON.encodeToString(JsonObject.serializer(), built.req)
}

// --- fixtures: one per deformation class the inversion touches ------------------------------------

/** cache_control at every legal depth: system blocks, message blocks, tool_result inner content,
 *  tools, tool_choice, and an unknown top-level field the verbatim copy owns. */
private const val CACHE_CONTROL_FIXTURE = """
{"model":"claude-kimi--k3","max_tokens":4096,
 "system":[{"type":"text","text":"sys prefix","cache_control":{"type":"ephemeral"}}],
 "metadata":{"user_id":"u-1","cache_control":{"type":"ephemeral"}},
 "service_tier":"auto",
 "messages":[
   {"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}]},
   {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"run","input":{"a":1}}]},
   {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","cache_control":{"type":"ephemeral"},
     "content":[{"type":"text","text":"out","cache_control":{"type":"ephemeral"}}]}]}],
 "tools":[{"name":"run","description":"d","input_schema":{"type":"object","properties":{}},
   "cache_control":{"type":"ephemeral"},"strict":true}],
 "tool_choice":{"type":"auto","cache_control":{"type":"ephemeral"}}}
"""

/** A properties chain N levels deep — built programmatically so the braces are correct by
 *  construction (MfjsSanitizer collapses anything past depth 10 to a bare object). */
private fun deepChain(levels: Int): String = buildString {
    repeat(levels) { i -> append("""{"type":"object","properties":{"l${i + 1}":""") }
    append("""{"type":"string"}""")
    repeat(levels) { append("}}") }
}

/** Every MFJS reduction: tuple items, prefixItems, format, exclusiveMinimum, min/maxContains,
 *  title/$schema/$comment, a ref with siblings, a typeless node, and depth past the cap. */
private val MFJS_FIXTURE = """
{"model":"m","messages":[{"role":"user","content":"go"}],
 "tools":[{"name":"deep","input_schema":{
   "${'$'}schema":"https://json-schema.org/draft/2020-12/schema","title":"Deep","${'$'}comment":"note",
   "type":"object",
   "properties":{
     "tuple":{"type":"array","items":[{"type":"string"},{"type":"number"}]},
     "prefixed":{"type":"array","prefixItems":[{"type":"string"}],"items":{"type":"number"}},
     "when":{"type":"string","format":"date-time"},
     "bounded":{"type":"integer","exclusiveMinimum":0,"exclusiveMaximum":10},
     "contains":{"type":"array","minContains":1,"maxContains":3,"contains":{"type":"string"}},
     "reffed":{"${'$'}ref":"#/definitions/x","description":"sibling kept?","type":"object"},
     "typeless":{"properties":{"inner":{"type":"string"}}},
     "nested":${deepChain(13)}
   },
   "required":["tuple"]}}]}
"""

/** thinking budget -> adaptive + output_config.effort ladder; a client output_config is dropped. */
private const val THINKING_FIXTURE = """
{"model":"m","messages":[{"role":"user","content":"think"}],
 "thinking":{"type":"enabled","budget_tokens":30000},
 "output_config":{"effort":"client-chosen"},
 "temperature":0.7,"top_p":0.9,"top_k":40}
"""

/** compact turn: built exactly like a turn since 2026-09-05 — tools, tool_choice and the string
 *  system ride verbatim; the golden is byte-equal to what the same body builds as a turn. */
private const val COMPACT_FIXTURE = """
{"model":"m","system":"be brief","messages":[{"role":"user","content":"summarize"}],
 "tools":[{"name":"run","input_schema":{"type":"object"}}],"tool_choice":{"type":"auto"},
 "thinking":{"type":"enabled","budget_tokens":9000}}
"""

/** the block allowlist: redacted_thinking / document / search_result are DROPPED today, and an
 *  EMPTY thinking block is dropped whether or not it carries a signature — only thinking that
 *  contains thinking rides, signature verbatim. The first block below is the shape that shipped a
 *  dead turn to a live session (Anthropic answers it with 400 "each thinking block must contain
 *  thinking", and every retry resends it), so it must never appear in the golden again. */
private const val BLOCK_ALLOWLIST_FIXTURE = """
{"model":"m","messages":[{"role":"assistant","content":[
  {"type":"thinking","thinking":"","signature":"sig-empty-but-signed"},
  {"type":"thinking","thinking":"kept","signature":"sig-abc"},
  {"type":"thinking","thinking":"   ","signature":""},
  {"type":"redacted_thinking","data":"enc-blob"},
  {"type":"document","source":{"type":"text","data":"doc"}},
  {"type":"search_result","content":[{"type":"text","text":"r"}]},
  {"type":"text","text":"after"}]}]}
"""

class PassthroughGoldenTest {

    @Test
    fun `cache_control stripping and verbatim field copy are byte-stable`() {
        assertGolden("request-cache-control.json", buildKimi(CACHE_CONTROL_FIXTURE))
    }

    @Test
    fun `mfjs schema sanitizing is byte-stable`() {
        assertGolden("request-mfjs-schema.json", buildKimi(MFJS_FIXTURE))
    }

    @Test
    fun `adaptive thinking rewrite and effort ladder are byte-stable`() {
        assertGolden("request-thinking-adaptive.json", buildKimi(THINKING_FIXTURE))
    }

    @Test
    fun `compact turn shape is byte-stable`() {
        assertGolden("request-compact.json", buildKimi(COMPACT_FIXTURE, compact = true))
    }

    @Test
    fun `content block allowlist is byte-stable`() {
        assertGolden("request-block-allowlist.json", buildKimi(BLOCK_ALLOWLIST_FIXTURE))
    }

    // --- translator goldens ----------------------------------------------------------------------

    @Test
    fun `signature synthesis on an unsigned thinking block is byte-stable`() = runTest {
        val sink = Recorder()
        val outcome = PassthroughStreamTranslator(ctx(), KIMI_QUIRKS)
            .driveTurn(UNSIGNED_THINKING_EVENTS.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success) { "fixture must be a clean turn, got $outcome" }
        assertGolden("translator-unsigned-thinking.txt", sink.calls.joinToString("\n"))
    }

    @Test
    fun `an upstream-signed thinking block is byte-stable and never double-signed`() = runTest {
        val sink = Recorder()
        PassthroughStreamTranslator(ctx(), KIMI_QUIRKS).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""),
                ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
                ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}"""),
                ev(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":""" +
                        """"real-sig"}}""",
                ),
                ev("""{"type":"content_block_stop","index":0}"""),
                ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""),
                ev("""{"type":"message_stop"}"""),
            ).asFlow(),
            sink,
        )
        assertGolden("translator-signed-thinking.txt", sink.calls.joinToString("\n"))
    }

    /** The provider-tagged failure text is user-facing on every head that runs this dialect. */
    @Test
    fun `provider-tagged failure text is byte-stable`() = runTest {
        val sink = Recorder()
        val outcome = PassthroughStreamTranslator(ctx(), KIMI_QUIRKS).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
                ev("""{"type":"error","error":{"type":"overloaded_error","message":"upstream busy"}}"""),
            ).asFlow(),
            sink,
        )
        assertGolden(
            "translator-failure-text.txt",
            failureSubject(outcome as TurnOutcome.Failure),
            subject = "the failure TYPE and its provider-tagged message",
        )
    }
}

/** The truncation shape: a thinking block with NO signature_delta. Kimi never signs; Anthropic
 *  always does, which is why the claude head must not inherit this synthesis. */
private val UNSIGNED_THINKING_EVENTS = listOf(
    ev("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""),
    ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
    ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}"""),
    ev("""{"type":"content_block_stop","index":0}"""),
    ev("""{"type":"content_block_start","index":1,"content_block":{"type":"text"}}"""),
    ev("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"done"}}"""),
    ev("""{"type":"content_block_stop","index":1}"""),
    ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""),
    ev("""{"type":"message_stop"}"""),
)

// --- harness ------------------------------------------------------------------------------------

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun ctx() = PassthroughTurnContext({ false }, { null }, 180_000, 900_000)

/** Records the sink call sequence as a stable transcript (mirrors the Rec in the translator test). */
private class Recorder : WireSink {
    val calls = mutableListOf<String>()
    private var n = 0
    override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
    override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
    override suspend fun openTool(id: String, name: String) =
        WireBlockIndex(n++).also { calls.add("openTool:$id:$name") }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json:$partialJson") }
    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) { calls.add("sig:$signature") }
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) { calls.add("redacted:$data") }
}
