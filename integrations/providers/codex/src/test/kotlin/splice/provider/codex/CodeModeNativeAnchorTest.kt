// NEW: native replay survives canonical-to-client offset changes without accepting edited payloads.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RoundBody

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
