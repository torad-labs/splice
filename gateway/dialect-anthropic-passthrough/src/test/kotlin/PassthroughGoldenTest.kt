// NEW (CH-1, campaign claude-head): the BYTE-IDENTITY WALL for the kimi passthrough.
//
// WHY THIS EXISTS: the claude head turns this dialect's Kimi-shaped deformations — MFJS schema
// sanitizing, the content-block allowlist, cache_control stripping, the adaptive-thinking rewrite,
// signature synthesis — into opt-in quirks with NEUTRAL defaults (CH-2). Kimi's wire bytes must not
// move by one character across that inversion, and "the unit tests still pass" is a weaker claim
// than it sounds: those tests assert PROPERTIES, so a rewrite that changes field ORDER, drops a
// verbatim-forwarded unknown field, or re-nests output_config passes them all while changing what
// Kimi receives (and with it prompt-cache stability). This pins the actual bytes.
//
// TWO LAYERS, deliberately (the gate's own gen/selftest idiom):
//   1. GOLDENS — built request bytes and translator call transcripts, compared to committed files.
//   2. CANARY — the same fixtures built with a deformation deliberately flipped MUST differ from
//      the golden. Without it a golden can rot into vacuity (pinning a builder that no longer
//      deforms anything still "passes"), which is the failure mode this wall exists to prevent.
//
// GOLDEN FILES ARE READ-ONLY to every later campaign item. If a change moves these bytes, that
// change is wrong — kimi behavior is frozen. Regenerate ONLY when the operator has decided kimi's
// wire genuinely changes:
//   UPDATE_GOLDENS=true ./gradlew :dialect-anthropic-passthrough:test --rerun-tasks
// then READ THE DIFF before committing it. (An env var, not a -D system property: the shared Test
// convention forwards neither, and test workers DO inherit the environment — so this needs no
// build-file change and cannot silently no-op.)
//
// CH-2 NOTE: [KIMI_QUIRKS] below is the ONE line in this file that may change — it must always
// construct KIMI's deformation set (today: the constructor defaults; after the inversion: every
// knob explicitly ON). The .json/.txt files under resources/goldens/ never change.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.parse.parseAnthropicBody
import splice.core.turn.TurnOutcome
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughRequestBuilder
import splice.dialect.passthrough.PassthroughStreamTranslator
import splice.dialect.passthrough.PassthroughTurnContext
import splice.spi.WireSink
import java.nio.file.Files
import java.nio.file.Path

/** KIMI's deformation set — see the CH-2 note in the file header. */
private val KIMI_QUIRKS = PassthroughQuirks(providerTag = "kimi")

private val GOLDEN_DIR: Path = Path.of("src", "test", "resources", "goldens")
private val JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
private val UPDATING = System.getenv("UPDATE_GOLDENS") == "true"

private fun assertGolden(name: String, actual: String) {
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
        "KIMI WIRE BYTES MOVED ($name). Kimi behavior is frozen by the claude-head campaign; a diff " +
            "here means the change under test altered what Kimi receives. Fix the change, not the " +
            "golden. If the operator has decided kimi's wire genuinely changes, regenerate with " +
            "UPDATE_GOLDENS=true and read the diff."
    }
}

private fun buildKimi(
    json: String,
    compact: Boolean = false,
    quirks: PassthroughQuirks = KIMI_QUIRKS,
): String {
    val built = PassthroughRequestBuilder(quirks).build(
        parseAnthropicBody(json),
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

/** compact turn: tools + tool_choice dropped, compaction directive appended to a string system. */
private const val COMPACT_FIXTURE = """
{"model":"m","system":"be brief","messages":[{"role":"user","content":"summarize"}],
 "tools":[{"name":"run","input_schema":{"type":"object"}}],"tool_choice":{"type":"auto"},
 "thinking":{"type":"enabled","budget_tokens":9000}}
"""

/** the block allowlist: redacted_thinking / document / search_result are DROPPED today, and an
 *  empty unsigned thinking block is dropped while a signed one rides verbatim. */
private const val BLOCK_ALLOWLIST_FIXTURE = """
{"model":"m","messages":[{"role":"assistant","content":[
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
        val outcome = PassthroughStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""),
                ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
                ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}"""),
                // NO signature_delta — the truncation shape. Kimi never signs; Anthropic always does,
                // which is why the claude head must not inherit this synthesis (spec Eli finding 11).
                ev("""{"type":"content_block_stop","index":0}"""),
                ev("""{"type":"content_block_start","index":1,"content_block":{"type":"text"}}"""),
                ev("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"done"}}"""),
                ev("""{"type":"content_block_stop","index":1}"""),
                ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""),
                ev("""{"type":"message_stop"}"""),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success) { "fixture must be a clean turn, got $outcome" }
        assertGolden("translator-unsigned-thinking.txt", sink.calls.joinToString("\n"))
    }

    @Test
    fun `an upstream-signed thinking block is byte-stable and never double-signed`() = runTest {
        val sink = Recorder()
        PassthroughStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""),
                ev("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
                ev("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}"""),
                ev(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"real-sig"}}""",
                ),
                ev("""{"type":"content_block_stop","index":0}"""),
                ev("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""),
                ev("""{"type":"message_stop"}"""),
            ).asFlow(),
            sink,
        )
        assertGolden("translator-signed-thinking.txt", sink.calls.joinToString("\n"))
    }

    /** The provider-tagged failure text is user-facing on every head that runs this dialect, so it
     *  is pinned too: CH-2 makes it providerTag-driven, and kimi's rendering must not move. */
    @Test
    fun `provider-tagged failure text is byte-stable`() = runTest {
        val sink = Recorder()
        val outcome = PassthroughStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
                ev("""{"type":"error","error":{"type":"overloaded_error","message":"upstream busy"}}"""),
            ).asFlow(),
            sink,
        )
        assertGolden("translator-failure-text.txt", outcome.toString())
    }

    // --- the canary: a golden that cannot detect a deformation is worthless ------------------------

    /** Flipping a deformation MUST move the bytes. This is the permanent form of CH-1's red proof:
     *  it fails if the builder ever stops deforming (i.e. if a golden rots into vacuity), which is
     *  exactly what CH-2's inversion would do to kimi if it wired the neutral defaults by mistake. */
    @Test
    fun `canary — flipping a deformation moves the bytes away from the golden`() {
        val goldenThinking = Files.readString(GOLDEN_DIR.resolve("request-thinking-adaptive.json"))
        val neutralThinking = buildKimi(
            THINKING_FIXTURE,
            quirks = KIMI_QUIRKS.copy(mapThinkingToAdaptive = false),
        ) + "\n"
        assertNotEquals(goldenThinking, neutralThinking) {
            "the adaptive-thinking golden no longer detects the rewrite — the wall has gone vacuous"
        }

        val goldenCache = Files.readString(GOLDEN_DIR.resolve("request-cache-control.json"))
        val strippedSampling = buildKimi(
            THINKING_FIXTURE,
            quirks = KIMI_QUIRKS.copy(stripSamplingParams = true),
        ) + "\n"
        assertNotEquals(goldenCache, strippedSampling)
        assertTrue(goldenCache.contains("\"metadata\"")) {
            "the cache_control golden must still carry the verbatim-copied unknown fields it pins"
        }
        assertTrue(!goldenCache.contains("cache_control")) {
            "the cache_control golden must show cache_control fully stripped — it pins the deformation"
        }
    }
}

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
