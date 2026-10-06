// NEW: native replay survives canonical-to-client offset changes without accepting edited payloads.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.RoundBody
import java.nio.file.Files
import java.nio.file.Path

internal class CodeModeNativeAnchorTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"first synthetic request"}""")
    private val latest = item("""{"role":"user","content":"next synthetic request"}""")
    private val native = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"synthetic"}""")
    private val preface = item("""{"role":"assistant","phase":"commentary","content":"Synthetic preface"}""")

    @Test
    fun `a missing older opaque anchor does not abandon unchanged native client history`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(first) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(1, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `duplicated canonical prose does not shift a later unchanged native out of its claim`() {
        val old = record(listOf(first), emptyList(), "old").apply {
            phase = CodeModePhase.COMPLETED
            continuity = listOf(preface)
            output = "done"
        }
        val baseline = listOf(first, preface, outer("old"), output("old"), preface, native, latest)
        val active = record(baseline, listOf(old), "active")
        val client = listOf(first, preface) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(1, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `an edited native payload with a missing older anchor is still rejected`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val edited = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"edited synthetic"}""")
        val client = listOf(first) + callbacks("old", 2) + edited + latest + callbacks("active", 1)

        val error = history.restoreBaseline(body(client), active).error
        assertEquals("code-mode native discovery history was edited", error)
    }

    @Test
    fun `a native moved away from an intact adjacent anchor is still rejected`() {
        val baseline = listOf(first, native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(native, first, latest) + callbacks("active", 1)

        val error = history.restoreBaseline(body(client), active).error
        assertEquals("code-mode native discovery history was edited", error)
        assertTrue(active.replayAnchors?.native?.values?.all { it.logicalTail == 0 } == true)
    }

    @Test
    fun `an absent native with a retired prior anchor restores before its following stable item`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(first) + callbacks("old", 2) + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        val items = input(checkNotNull(restored.bodyJson))
        assertEquals(items.indexOf(latest) - 1, items.indexOf(native))
    }

    @Test
    fun `counted native witnesses retain repeats and reject extra or reordered occurrences`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle) +
            listOf(outer("older"), output("older"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2) + native + middle + callbacks("older", 2)
        val client = prefix + native + latest + callbacks("active", 1)
        val restored = history.restoreBaseline(body(client), active)
        assertNull(restored.error)
        assertEquals(2, input(checkNotNull(restored.bodyJson)).count { it == native })

        val extra = prefix + native + middle + native + latest + callbacks("active", 1)
        val extraError = history.restoreBaseline(body(extra), active).error
        assertEquals("code-mode native discovery history was edited", extraError)

        val other = item("""{"type":"reasoning","id":"reason-other","encrypted_content":"other synthetic"}""")
        val distinct = baseline.toMutableList().apply { this[7] = other }
        val separate = record(distinct, emptyList(), "active")
        val swapped = listOf(first) + callbacks("old", 2) + other + middle + callbacks("older", 2) +
            native + latest + callbacks("active", 1)
        val reorderedError = history.restoreBaseline(body(swapped), separate).error
        assertEquals("code-mode native discovery history was edited", reorderedError)
    }

    @Test
    fun `a later repeated native is counted inside its own stable anchor bounds`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val old = record(listOf(first, native, middle), emptyList(), "old").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
        }
        val baseline = listOf(first, native, middle, outer("old"), output("old"), native, latest)
        val active = record(baseline, listOf(old), "active")
        val client = listOf(first, native, middle) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(2, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `an inherited native survives a child repeat beyond the parent callback bound`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val parentBaseline = listOf(first, outer("retired"), output("retired"), native, middle)
        val parent = record(parentBaseline, emptyList(), "parent").apply {
            phase = CodeModePhase.COMPLETED
            output = "done"
        }
        val baseline = parentBaseline + listOf(outer("parent"), output("parent"), native, latest)
        val active = record(baseline, listOf(parent), "active")
        parent.replayAnchors = parent.replayAnchors?.copy(nativeFollowing = emptyMap())
        active.replayAnchors = active.replayAnchors?.copy(nativeFollowing = emptyMap())
        val client = listOf(first) + callbacks("retired", 2) + native + middle +
            callbacks("parent", 1) + native + latest + callbacks("active", 1)
        val request = body(client)
        assertNull(history.restoreBaseline(request, active).error, "the flat capture is the same history")

        val capture = CodeModeNativeChain.capture(active.nativeSegments, parent)
        assertEquals(parent, capture.parent)
        assertEquals(listOf(6), capture.segments.map { it.logicalOffset })
        active.nativeSegments = capture.segments
        active.nativeBaseId = parent.id
        active.nativeParent = parent

        val restored = history.restoreBaseline(request, active)
        assertNull(restored.error)
        assertEquals(client, input(checkNotNull(restored.bodyJson)))
    }

    @Test
    fun `an absent native restores before the first of multiple following stable items`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val suffix = listOf(middle, latest) + callbacks("active", 1)
        assertNull(history.restoreBaseline(body(prefix + native + suffix), active).error)

        val restored = history.restoreBaseline(body(prefix + suffix), active)
        assertNull(restored.error)
        assertEquals(prefix + native + suffix, input(checkNotNull(restored.bodyJson)))
    }

    @Test
    fun `an absent native is not guessed when the client also removed its immediate following witness`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val suffix = listOf(latest) + callbacks("active", 1)
        assertNull(history.restoreBaseline(body(prefix + native + suffix), active).error)

        assertEquals(
            "code-mode native discovery history was edited",
            history.restoreBaseline(body(prefix + suffix), active).error,
            "the surviving later item must not substitute for the native's missing immediate witness",
        )
    }

    @Test
    fun `a following witness still rejects edited or moved native payloads`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val edited = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"edited synthetic"}""")
        val corrupted = listOf(
            prefix + edited + middle + latest + callbacks("active", 1),
            prefix + middle + native + latest + callbacks("active", 1),
        )
        for (client in corrupted) {
            assertEquals(
                "code-mode native discovery history was edited",
                history.restoreBaseline(body(client), active).error,
            )
        }
    }

    @Test
    fun `legacy journals without following metadata load empty and retain their native history`(@TempDir dir: Path) {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val encoded = Json.encodeToString(CodeModePersistedState(records = listOf(active.snapshot())))
        val root = Json.parseToJsonElement(encoded).jsonObject
        val rows = root.getValue("records").jsonArray.map { raw ->
            val row = raw.jsonObject
            val anchors = row.getValue("replayAnchors").jsonObject
            assertTrue(anchors.getValue("nativeFollowing").jsonObject.isNotEmpty())
            JsonObject(row + ("replayAnchors" to JsonObject(anchors - "nativeFollowing")))
        }
        val file = dir.resolve("legacy.jsonl")
        Files.writeString(file, JsonObject(root + ("records" to JsonArray(rows))).toString() + "\n")

        val restored = CodeModeStateJournal.read(file, Json).records.single().restore()

        assertTrue(restored.replayAnchors?.nativeFollowing?.isEmpty() == true)
        val client = listOf(first) + callbacks("old", 2) + native + latest + callbacks("active", 1)
        val placed = history.restoreBaseline(body(client), restored)
        assertNull(placed.error)
        assertEquals(client, input(checkNotNull(placed.bodyJson)))
    }

    private fun record(items: List<JsonElement>, completed: List<CodeModeRecord>, id: String): CodeModeRecord {
        val baseline = checkNotNull(history.anchoredBoundary(body(items), completed))
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            outer = outer(id),
            outerCallId = id,
            source = "return 'synthetic';",
            phase = CodeModePhase.ACTIVE,
            updatedAt = 0,
            lastDigest = "synthetic-request",
            baselineInputCount = baseline.fullCount,
            baselineInputDigest = baseline.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = baseline.logicalCount,
            baselineLogicalDigest = baseline.logicalDigest,
            nativeSegments = baseline.nativeSegments,
            continuity = emptyList(),
            continuityReplay = emptyList(),
        ).also {
            it.replayAnchors = baseline.replayAnchors
            it.pending += CodeModePending("runtime-$id", "callback-$id-0", "Read", JsonObject(emptyMap()), true)
        }
    }

    private fun callbacks(id: String, count: Int): List<JsonElement> = (0 until count).flatMap { at ->
        listOf(
            item("""{"type":"function_call","call_id":"callback-$id-$at","name":"Read","arguments":"{}"}"""),
            item("""{"type":"function_call_output","call_id":"callback-$id-$at","output":"synthetic result"}"""),
        )
    }

    private fun outer(id: String): JsonObject = item(
        """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 'synthetic';"}""",
    ) as JsonObject

    private fun output(id: String): JsonElement =
        item("""{"type":"custom_tool_call_output","call_id":"$id","output":"done"}""")

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun input(text: String): List<JsonElement> =
        ((Json.parseToJsonElement(text) as JsonObject)["input"] as JsonArray).toList()

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
