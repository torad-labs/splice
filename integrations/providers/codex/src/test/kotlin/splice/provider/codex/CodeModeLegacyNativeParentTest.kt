// NEW: a legacy ancestor cannot claim descendant natives its own baseline never captured.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeNativeOrigin
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Files
import java.nio.file.Path

internal class CodeModeLegacyNativeParentTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"synthetic first request"}""")
    private val native = listOf(
        item("""{"type":"reasoning","id":"synthetic-reason","encrypted_content":"synthetic encrypted bytes"}"""),
        item("""{"type":"tool_search_call","call_id":"synthetic-search","arguments":"{}"}"""),
        item("""{"type":"tool_search_output","call_id":"synthetic-search","tools":[{"name":"Unused"}]}"""),
    )

    @Test
    fun `a legacy ancestor cannot reject every fresh descendant with unchanged native replay`(@TempDir dir: Path) {
        val fixture = fixture(dir, copies = 1, legacy = true)
        val completed = (fixture.retired + fixture.root).toMutableList()
        var client = fixture.prefix + callbacks(fixture.root)
        val rejected = mutableListOf<String>()
        repeat(6) { step ->
            val fresh = fresh(completed, client, step)
            assertNotNull(fresh.record.nativeParent)
            assertTrue(generateSequence(fresh.record) { it.nativeParent }.any { it === fixture.root })
            val expected = natives(fresh.client)
            val restored = history.restoreBaseline(body(fresh.client), fresh.record)
            if (restored.error == null) {
                assertEquals(
                    expected,
                    natives(input(checkNotNull(restored.bodyJson))),
                    "native wire items stay byte-identical",
                )
            } else {
                rejected += restored.nativeRejection?.logFields() ?: restored.error.orEmpty()
            }
            fresh.record.phase = CodeModePhase.COMPLETED
            fresh.record.output = "done"
            completed += fresh.record
            client = fresh.client
        }
        assertEquals(emptyList<String>(), rejected, "a legacy root must not impose uncaptured descendant occurrences")
    }

    @Test
    fun `a fresh descendant reports its parent's counted failure before an unexpected offset`(@TempDir dir: Path) {
        val fixture = fixture(dir, copies = 2, legacy = false)
        val fresh = fresh(fixture.retired + fixture.root, fixture.prefix + callbacks(fixture.root), 0)
        val firstNative = fresh.client.indexOf(native.first())
        val secondNative = fresh.client.indexOf(native.first(), firstNative + 1)
        val missing = fresh.client.take(secondNative) + fresh.client.drop(secondNative + native.size)
        val codec = CodexCodeModeHistoryCodec(Json)
        val projection = codec.conversation(codec.projection.project(JsonArray(missing))).body
        val index = CodeModeHistoryIndex(projection.logicalItems, codec)
        val replay = projection.replayItems.groupBy { it.logicalOffset }
            .mapValues { (_, parts) -> parts.flatMap { it.items } }
        val parent = index.nativeOffset(
            CodeModeNativeOrigin(fixture.root, fixture.root.nativeSegments.first()),
            fresh.record,
            replay,
            emptyList(),
        )
        assertEquals(CodeModeNativeBranch.NATIVE_ORDER, parent.branch)
        val restored = history.restoreBaseline(body(missing), fresh.record)
        assertEquals("code-mode native discovery history was edited: nativeOrder", restored.error)
        assertEquals(
            parent.branch,
            restored.nativeRejection?.branch,
            "the actual parent failure is not an unexpected child",
        )
    }

    @Test
    fun `an unchanged chain remains placeable after its legacy root and descendants reload`(@TempDir dir: Path) {
        val fixture = fixture(dir, copies = 1, legacy = true)
        val fresh = fresh(fixture.retired + fixture.root, fixture.prefix + callbacks(fixture.root), 0)
        val file = dir.resolve("complete-chain.jsonl")
        val records = fixture.retired + fixture.root + fresh.record
        val encoded = Json.encodeToString(CodeModePersistedState(records = records.map { it.snapshot() }))
        Files.writeString(file, encoded + "\n")
        val loaded = CodeModeStateJournal.read(file, Json).records.map { it.restore() }
        CodeModeNativeChain.link(loaded)
        val owner = loaded.last()
        assertNotNull(owner.nativeParent)
        assertTrue(loaded[fixture.retired.size].replayAnchors?.nativeFollowing?.isEmpty() == true)
        val restored = history.restoreBaseline(body(fresh.client), owner)
        assertNull(restored.error, "reload cannot turn an intact chain into an edit")
        assertEquals(natives(fresh.client), natives(input(checkNotNull(restored.bodyJson))))
    }

    @Test
    fun `captured ancestors still reject payload order extra and missing occurrence edits`(@TempDir dir: Path) {
        for (legacy in listOf(false, true)) {
            val fixture = fixture(dir, copies = 2, legacy = legacy)
            val fresh = fresh(fixture.retired + fixture.root, fixture.prefix + callbacks(fixture.root), 0)
            val intact = history.restoreBaseline(body(fresh.client), fresh.record)
            assertNull(intact.error, "the intact counted history is the control")
            assertEquals(3, input(checkNotNull(intact.bodyJson)).count { it == native.first() })
            val firstNative = fresh.client.indexOf(native.first())
            val secondNative = fresh.client.indexOf(native.first(), firstNative + 1)
            val edited = fresh.client.toMutableList().apply {
                this[firstNative] = JsonObject(native.first().jsonObject + ("encrypted_content" to JsonPrimitive("changed")))
            }
            val reordered = fresh.client.take(firstNative) + listOf(native[1], native[2], native[0]) +
                fresh.client.drop(firstNative + native.size)
            val missing = fresh.client.take(secondNative) + fresh.client.drop(secondNative + native.size)
            val extra = fresh.client.take(secondNative) + native + fresh.client.drop(secondNative)
            for (client in listOf(edited, reordered, missing, extra)) {
                val rejected = history.restoreBaseline(body(client), fresh.record)
                assertTrue(
                    rejected.nativeRejection?.branch in setOf(CodeModeNativeBranch.PAYLOAD, CodeModeNativeBranch.NATIVE_ORDER),
                )
                assertEquals(checkNotNull(rejected.nativeRejection).branch.reason(), rejected.error)
            }
        }
    }

    @Test
    fun `an owner's own repeats and continuity translate from capture slots beyond its raw callback bound`() {
        val middle = item("""{"role":"user","content":"synthetic middle"}""")
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val next = item("""{"role":"user","content":"synthetic next"}""")
        val retired = listOf("retired-a", "retired-b").map { id ->
            record(listOf(first), emptyList(), id).apply {
                phase = CodeModePhase.COMPLETED
                output = "done"
            }
        }
        val baseline = listOf(first, retired[0].outer, output(retired[0].outerCallId)) + native + middle +
            listOf(retired[1].outer, output(retired[1].outerCallId)) + native + latest
        val parent = record(baseline, retired, "parent").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
            continuityReplay = listOf(CodeModeNativeSegment(0, native))
            replayAnchors = replayAnchors?.copy(nativeFollowing = emptyMap())
        }
        val child = record(
            baseline + native + parent.outer + output(parent.outerCallId) + next,
            retired + parent,
            "child",
        )
        val capture = CodeModeNativeChain.capture(child.nativeSegments, parent)
        assertEquals(parent, capture.parent)
        child.nativeSegments = capture.segments
        child.nativeParent = capture.parent
        child.nativeBaseId = parent.id
        child.replayAnchors = child.replayAnchors?.copy(nativeFollowing = emptyMap())
        val client = listOf(first) + native + middle + native + latest + native + callbacks(parent) + next + callbacks(child)
        val codec = CodexCodeModeHistoryCodec(Json)
        val projection = codec.conversation(codec.projection.project(JsonArray(client))).body
        val index = CodeModeHistoryIndex(projection.logicalItems, codec)
        assertTrue(parent.nativeSegments.any { it.logicalOffset > index.owned(parent).first() })
        val restored = history.restoreBaseline(body(client), child)
        assertNull(restored.error, "canonical capture slots and raw callback slots are different coordinates")
        assertEquals(client, input(checkNotNull(restored.bodyJson)))
        assertEquals(3, input(checkNotNull(restored.bodyJson)).count { it == native.first() })
    }

    @Test
    fun `an adjacent witness does not excuse reordered or extra native payloads`() {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val active = record(listOf(first) + native + latest, emptyList(), "adjacent")
        val suffix = listOf(latest) + callbacks(active)
        val intact = listOf(first) + native + suffix
        assertNull(history.restoreBaseline(body(intact), active).error)
        assertNull(history.restoreBaseline(body(listOf(first) + suffix), active).error, "absence alone is restorable")
        val reordered = listOf(first, native[1], native[2], native[0]) + suffix
        val extra = listOf(first) + native + native.first() + suffix
        for (client in listOf(reordered, extra)) {
            val restored = history.restoreBaseline(body(client), active)
            assertEquals("code-mode native discovery history was edited: nativeOrder", restored.error)
        }
    }

    @Test
    fun `an absent native survives its own exec follower becoming ordinary callbacks`() {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val retired = record(listOf(first), emptyList(), "retired").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
        }
        val oldBaseline = listOf(first, retired.outer, output(retired.outerCallId))
        val old = record(oldBaseline, listOf(retired), "old").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
            continuityReplay = listOf(CodeModeNativeSegment(0, native))
            nativeParent = retired
            nativeBaseId = retired.id
        }
        val baseline = oldBaseline + native + old.outer + output(old.outerCallId) + latest
        val active = record(baseline, listOf(retired, old), "active")
        val capture = CodeModeNativeChain.capture(active.nativeSegments, old)
        assertEquals(old, capture.parent)
        active.nativeSegments = capture.segments
        active.nativeParent = old
        active.nativeBaseId = old.id
        assertTrue(active.replayAnchors?.nativeFollowing?.containsKey(old.baselineLogicalCount) == true)
        val client = listOf(first) + callbacks(retired) + callbacks(old) + latest + callbacks(active)
        assertEquals(emptyList<String>(), natives(client), "the ordinary client never carries the native group")
        val restored = history.restoreBaseline(body(client), active)
        assertNull(restored.error, "the known producer's authenticated callback places the omitted native")
        val expected = listOf(first) + callbacks(retired) + native + callbacks(old) + latest + callbacks(active)
        assertEquals(expected, input(checkNotNull(restored.bodyJson)))
        val rewritten = history.canonicalize(body(client), listOf(retired, old))
        assertTrue(rewritten.omitted.isEmpty())
        assertNull(history.restoreBaseline(checkNotNull(rewritten.body), active).error)
    }

    @Test
    fun `an adjacent legacy chain admits unchanged identical copies and complete absence`() {
        val latest = item("""{"role":"user","content":"synthetic latest"}""")
        val duplicate = native + native.first()
        val parent = record(listOf(first) + duplicate + latest, emptyList(), "duplicate-parent").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
            replayAnchors = replayAnchors?.copy(nativeFollowing = emptyMap())
        }
        val childBaseline = listOf(first) + duplicate + latest + parent.outer + output(parent.outerCallId)
        val child = record(childBaseline, listOf(parent), "duplicate-child")
        val capture = CodeModeNativeChain.capture(child.nativeSegments, parent)
        assertEquals(parent, capture.parent)
        child.nativeSegments = capture.segments
        child.nativeParent = parent
        child.nativeBaseId = parent.id
        child.replayAnchors = child.replayAnchors?.copy(nativeFollowing = emptyMap())
        val suffix = listOf(latest) + callbacks(parent) + callbacks(child)
        for (client in listOf(listOf(first) + duplicate + suffix, listOf(first) + suffix)) {
            val restored = history.restoreBaseline(body(client), child)
            assertNull(
                restored.error,
                "present=${natives(client).size} ${restored.nativeRejection?.logFields()}",
            )
        }
    }

    private fun fixture(dir: Path, copies: Int, legacy: Boolean): Fixture {
        val retired = mutableListOf<CodeModeRecord>()
        var canonical = listOf(first)
        var client = listOf(first)
        repeat(copies) { index ->
            val prior = record(canonical, retired, "retired-$index").apply {
                phase = CodeModePhase.COMPLETED
                output = "done"
            }
            retired += prior
            canonical = canonical + prior.outer + output(prior.outerCallId) + native
            client = client + callbacks(prior) + native
        }
        val old = item("""{"role":"user","content":"synthetic old context no longer replayed"}""")
        val rootBaseline = if (copies == 1) {
            canonical + listOf(
                item("""{"type":"function_call","call_id":"old-unowned","name":"Read","arguments":"{}"}"""),
                item("""{"type":"function_call_output","call_id":"old-unowned","output":"old synthetic result"}"""),
                old,
            )
        } else {
            canonical + old
        }
        val root = record(rootBaseline, retired, "legacy-root").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
        }
        assertTrue(
            root.replayAnchors?.nativeFollowing?.isNotEmpty() == true,
            "the saved pre-following schema really loses a field",
        )
        val kept = if (legacy) legacy(root, dir.resolve("legacy-root.jsonl")) else root
        if (legacy) assertTrue(kept.replayAnchors?.nativeFollowing?.isEmpty() == true)
        return Fixture(retired, kept, client)
    }

    private fun fresh(completed: List<CodeModeRecord>, prefix: List<JsonElement>, step: Int): Fresh {
        val request = prefix + item("""{"role":"user","content":"synthetic next request $step"}""") + native
        val canonical = history.canonicalize(body(request), completed)
        assertTrue(
            canonical.omitted.any { it.record.id == "legacy-root" },
            "the older logical baseline cannot place and stays ordinary",
        )
        val baseline = input(checkNotNull(canonical.bodyJson))
        val record = record(baseline, completed, "fresh-$step")
        val capture = CodeModeNativeChain.capture(record.nativeSegments, completed.last())
        record.nativeSegments = capture.segments
        record.nativeParent = capture.parent
        record.nativeBaseId = capture.parent?.id
        return Fresh(record, request + callbacks(record))
    }

    private fun legacy(record: CodeModeRecord, file: Path): CodeModeRecord {
        val encoded = Json.encodeToString(CodeModePersistedState(records = listOf(record.snapshot())))
        val state = Json.parseToJsonElement(encoded).jsonObject
        val saved = state.getValue("records").jsonArray.single().jsonObject
        val anchors = saved.getValue("replayAnchors").jsonObject
        val old = JsonObject(saved + ("replayAnchors" to JsonObject(anchors - "nativeFollowing")))
        Files.writeString(file, JsonObject(state + ("records" to JsonArray(listOf(old)))).toString() + "\n")
        return CodeModeStateJournal.read(file, Json).records.single().restore()
    }

    private fun record(items: List<JsonElement>, completed: List<CodeModeRecord>, id: String): CodeModeRecord {
        val boundary = checkNotNull(history.anchoredBoundary(body(items), completed))
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            outer = outer(id),
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

    private fun outer(id: String): JsonObject = item(
        """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 'synthetic';"}""",
    ).jsonObject

    private fun output(id: String): JsonElement = item(
        """{"type":"custom_tool_call_output","call_id":"$id","output":"done"}""",
    )

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun input(text: String): List<JsonElement> =
        Json.parseToJsonElement(text).jsonObject.getValue("input").jsonArray.toList()

    private fun natives(items: List<JsonElement>): List<String> {
        val boundary = checkNotNull(history.anchoredBoundary(body(items), emptyList()))
        return boundary.nativeSegments.flatMap { it.items }.map { it.toString() }
    }

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun List<JsonElement>.indexOf(item: JsonElement, start: Int): Int =
        (start until size).first { this[it] == item }

    private data class Fixture(val retired: List<CodeModeRecord>, val root: CodeModeRecord, val prefix: List<JsonElement>)
    private data class Fresh(val record: CodeModeRecord, val client: List<JsonElement>)
}
