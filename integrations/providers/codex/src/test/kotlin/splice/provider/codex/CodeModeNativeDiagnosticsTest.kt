// NEW: native rejection reasons and request-local placement evidence cannot claim edits or leak wire data.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.InputDigest
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.diagnostics.CodeModeHistoryLog
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.provider.codex.state.diagnostics.CodeModeNativeEvidenceCapture
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Files
import java.nio.file.Path

internal class CodeModeNativeDiagnosticsTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"synthetic first request"}""")
    private val native = listOf(
        item("""{"type":"reasoning","id":"synthetic-reason","encrypted_content":"synthetic encrypted bytes"}"""),
        item("""{"type":"tool_search_call","call_id":"synthetic-search","arguments":"{}"}"""),
        item("""{"type":"tool_search_output","call_id":"synthetic-search","tools":[{"name":"Unused"}]}"""),
    )

    @Test
    fun `an absent native placement failure is not reported as a proven edit`() {
        val (_, restored) = unplaceable()
        assertEquals(CodeModeNativeBranch.ABSENT, restored.nativeRejection?.branch)
        assertEquals("code-mode native discovery history could not be placed: absent", restored.error)
    }

    @Test
    fun `an unplaceable native logs only numeric scope and fixed witness categories`() {
        val (active, restored) = unplaceable()
        assertEquals(
            "session none native_following=present native_branch=absent " +
                "native_owner_depth=0 native_bounds_lo=0 native_bounds_hi=4 " +
                "native_witness_source=source native_witness_kind=unknown native_witness_resolved=false " +
                "native_expected_occurrences=3 native_actual_occurrences=0",
            CodeModeHistoryLog.context(active, checkNotNull(restored.nativeRejection)),
        )
    }

    @Test
    fun `pre-v5 replay conflicts do not claim a proven client edit`() {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val baseline = listOf(first) + native + latest
        val original = record(baseline, "legacy-v4")
        val legacy = original.copy(
            metadataVersion = 4,
            baselineInputDigest = InputDigest.hex(JsonArray(baseline)),
            baselineLogicalDigest = InputDigest.hex(JsonArray(listOf(first, latest))),
        )
        val changed = JsonObject(native.first().jsonObject + ("encrypted_content" to JsonPrimitive("changed")))
        val client = listOf(first, changed) + native.drop(1) + latest + callbacks(legacy)
        assertEquals(
            "code-mode native replay conflicts with its captured baseline",
            history.restoreBaseline(body(client), legacy).error,
        )
    }

    @Test
    fun `every native branch has one pinned reason`() {
        assertEquals(
            mapOf(
                "nativeOrder" to "code-mode native discovery history was edited: nativeOrder",
                "absent" to "code-mode native discovery history could not be placed: absent",
                "counted" to "code-mode native discovery history could not be placed: counted",
                "payload" to "code-mode native discovery history was edited: payload",
                "unexpected-offset" to
                    "code-mode native discovery history conflicts with its captured position: unexpected-offset",
            ),
            CodeModeNativeBranch.entries.associate { it.wire to it.reason() },
        )
    }

    @Test
    fun `resolved witness kinds never log arbitrary captured type text`() {
        val privateKind = "synthetic-private-type-text"
        val follower = JsonObject(
            mapOf("type" to JsonPrimitive(privateKind), "content" to JsonPrimitive("private bytes")),
        )
        val active = record(listOf(first) + native + follower, "private-record")
        val codec = CodexCodeModeHistoryCodec(Json)
        val projection = codec.conversation(codec.projection.project(JsonArray(listOf(first) + native + follower))).body
        val index = CodeModeHistoryIndex(projection.logicalItems, codec)
        val evidence = CodeModeNativeEvidenceCapture.capture(index, active, active, active.nativeSegments.first(), 0..2)
        val line = CodeModeHistoryLog.context(
            active,
            CodeModeNativeRejection(true, CodeModeNativeBranch.ABSENT, evidence),
        )
        assertTrue(line.contains("native_witness_kind=other native_witness_resolved=true"))
        assertTrue(privateKind !in line && "private bytes" !in line && "private-record" !in line)
    }

    @Test
    fun `a missing restored ancestor cannot turn unchanged native occurrences into an edit`(@TempDir dir: Path) {
        val middle = item("""{"role":"user","content":"synthetic middle"}""")
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val baseline = listOf(first) + native + middle + native + latest
        val captured = record(baseline, "survivor")
        val delta = captured.copy(nativeSegments = captured.nativeSegments.drop(1)).also {
            it.replayAnchors = captured.replayAnchors
            it.nativeBaseId = "missing-parent"
        }
        val file = dir.resolve("partial-chain.jsonl")
        val saved = CodeModePersistedState(records = listOf(delta.snapshot()))
        Files.writeString(file, Json.encodeToString(saved) + "\n")
        val loaded = CodeModeStateJournal.read(file, Json).records.single().restore()
        CodeModeNativeChain.link(listOf(loaded))
        assertEquals(null, loaded.nativeParent)
        assertEquals("missing-parent", loaded.nativeBaseId)
        val client = baseline + callbacks(captured)
        val restored = history.restoreBaseline(body(client), loaded)
        assertEquals(CodeModeNativeBranch.UNEXPECTED, restored.nativeRejection?.branch)
        assertEquals(
            "code-mode native discovery history conflicts with its captured position: unexpected-offset",
            restored.error,
            "partial record retention cannot prove that the byte-unchanged client edited native history",
        )
    }

    @Test
    fun `a genuine extra occurrence inside a complete captured slot still reports an edit`() {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val baseline = listOf(first) + native + latest
        val active = record(baseline, "complete")
        val client = listOf(first) + native + native.first() + latest + callbacks(active)
        val restored = history.restoreBaseline(body(client), active)
        assertEquals(CodeModeNativeBranch.NATIVE_ORDER, restored.nativeRejection?.branch)
        assertEquals("code-mode native discovery history was edited: nativeOrder", restored.error)
    }

    @Test
    fun `missing ancestry cannot prove an order edit across an unresolved opaque witness`(@TempDir dir: Path) {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val older = record(listOf(first), "older")
        val oldOutput = item("""{"type":"custom_tool_call_output","call_id":"older","output":"done"}""")
        val baseline = listOf(first) + native + older.outer + oldOutput + native + latest
        val captured = record(baseline, "survivor")
        val delta = captured.copy(nativeSegments = captured.nativeSegments.drop(1)).also {
            it.replayAnchors = captured.replayAnchors
            it.nativeBaseId = "missing-parent"
        }
        val file = dir.resolve("partial-opaque-chain.jsonl")
        val saved = Json.encodeToString(CodeModePersistedState(records = listOf(delta.snapshot())))
        Files.writeString(file, saved + "\n")
        val loaded = CodeModeStateJournal.read(file, Json).records.single().restore()
        CodeModeNativeChain.link(listOf(loaded))
        val client = listOf(first) + native + callbacks(older) + native + latest + callbacks(captured)
        val restored = history.restoreBaseline(body(client), loaded)
        assertNull(restored.bodyJson, "incomplete placement must remain rejected")
        assertEquals(CodeModeNativeBranch.COUNT, restored.nativeRejection?.branch)
        assertEquals(
            "code-mode native discovery history could not be placed: counted",
            restored.error,
            "ordinary callbacks and missing parent state cannot certify an edited complete sequence",
        )
    }

    private fun unplaceable(): Pair<CodeModeRecord, CodeModeRewrite> {
        val middle = item("""{"role":"user","content":"synthetic private middle"}""")
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val old = record(listOf(first), "old")
        val baseline = listOf(first, old.outer, item("""{"type":"custom_tool_call_output","call_id":"old","output":"done"}""")) +
            native + middle + latest
        val active = record(baseline, "absent")
        val client = listOf(first) + callbacks(old) + latest + callbacks(active)
        return active to history.restoreBaseline(body(client), active)
    }

    private fun record(items: List<JsonElement>, id: String): CodeModeRecord {
        val boundary = checkNotNull(history.anchoredBoundary(body(items), emptyList()))
        val outer = item(
            """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 'synthetic';"}""",
        ).jsonObject
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            outer = outer,
            outerCallId = id,
            source = "return 'synthetic';",
            phase = CodeModePhase.ACTIVE,
            updatedAt = 0,
            lastDigest = "synthetic-request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = boundary.nativeSegments,
            continuity = emptyList(),
            continuityReplay = emptyList(),
        ).also {
            it.replayAnchors = boundary.replayAnchors
            it.pending += CodeModePending("runtime-$id", "callback-$id", "Read", JsonObject(emptyMap()), true)
            it.accepted.accept(mapOf("callback-$id" to CodeModeResult("callback-$id", "synthetic result")), emptyMap())
        }
    }

    private fun callbacks(record: CodeModeRecord): List<JsonElement> = listOf(
        item("""{"type":"function_call","call_id":"callback-${record.id}","name":"Read","arguments":"{}"}"""),
        item("""{"type":"function_call_output","call_id":"callback-${record.id}","output":"synthetic result"}"""),
    )

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
