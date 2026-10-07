// NEW: canonical emissions preserve each captured request's ordered prefix while admitting new completions.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeNativeChain
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult

internal class CodeModeCanonicalOrderTest {
    private val history = CodexCodeModeHistory(Json)
    private val prefix: List<JsonElement> = (0 until 48).map { at ->
        item("""{"role":"user","content":"synthetic request $at"}""")
    }
    private val earlierNative = reasoning("earlier")
    private val laterNative = reasoning("later")

    private data class Fixture(
        val completed: List<CodeModeRecord>,
        val owner: CodeModeRecord,
        val client: List<JsonElement>,
        val expected: List<JsonElement>,
        val captured: List<JsonElement>,
    )

    @Test
    fun `late parent callbacks cannot move an anchored callback-free record behind its descendants`() {
        val fixture = fixture(emptyList(), 4)
        assertEquals(listOf(48, 50, 52, 54), fixture.completed.map(CodeModeRecord::baselineLogicalCount))
        val rewritten = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), fixture.owner)
        assertTrue(rewritten.omitted.isEmpty())
        val restored = history.restoreBaseline(checkNotNull(rewritten.body), fixture.owner)
        assertNull(restored.error, "canonical parent-floor placement must not invert captured reasoning")
        assertEquals(bodyText(fixture.expected), rewritten.bodyJson)
    }

    @Test
    fun `a callback-free child still follows the newer stable user anchor it answered`() {
        val next = item("""{"role":"user","content":"synthetic newer request"}""")
        val fixture = fixture(listOf(next), 4)
        val rewritten = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), fixture.owner)
        assertTrue(rewritten.omitted.isEmpty())
        assertNull(history.restoreBaseline(checkNotNull(rewritten.body), fixture.owner).error)
        assertEquals(bodyText(fixture.expected), rewritten.bodyJson, "in-order canonical bytes stay unchanged")
    }

    @Test
    fun `owned callbacks cannot place a descendant before its hidden callback-free parent`() {
        val fixture = fixture(emptyList(), 0)
        val rewritten = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), fixture.owner)
        assertTrue(rewritten.omitted.isEmpty())
        assertNull(history.restoreBaseline(checkNotNull(rewritten.body), fixture.owner).error)
        assertEquals(bodyText(fixture.expected), rewritten.bodyJson)
    }

    @Test
    fun `a captured source-order tail places with a newly completed native bundle`() {
        assertCaptured(frozenFixture(false))
    }

    @Test
    fun `a later root keeps the older posted permutation instead of recapturing admission order`() {
        assertCaptured(frozenFixture(true))
    }

    @Test
    fun `appending a completion never changes the captured prefix`() {
        val fixture = fixture(emptyList(), 4)
        val before = fixture.completed.dropLast(1)
        val legacy = history.canonicalize(body(fixture.client), before)
        assertNotEquals(fixture.captured, input(checkNotNull(legacy.body)).take(fixture.captured.size))
        val first = history.canonicalize(body(fixture.client), before, emptyMap(), fixture.owner)
        val appended = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), fixture.owner)
        assertEquals(fixture.captured, input(checkNotNull(first.body)).take(fixture.captured.size))
        assertEquals(fixture.captured, input(checkNotNull(appended.body)).take(fixture.captured.size))
    }

    @Test
    fun `reversed completion enumeration cannot reverse an already posted prefix`() {
        val fixture = fixture(emptyList(), 0)
        val rewritten = history.canonicalize(
            body(fixture.client),
            fixture.completed.reversed(),
            emptyMap(),
            fixture.owner,
        )
        assertEquals(bodyText(fixture.expected), rewritten.bodyJson)
        assertNull(history.restoreBaseline(checkNotNull(rewritten.body), fixture.owner).error)
    }

    @Test
    fun `already posted and raw callback-free no-capture histories are idempotent`() {
        for (callbacks in listOf(0, 4)) {
            val fixture = fixture(emptyList(), callbacks)
            val first = history.canonicalize(body(fixture.expected), fixture.completed)
            val second = history.canonicalize(checkNotNull(first.body), fixture.completed)
            assertEquals(first.bodyJson, second.bodyJson)
        }
        val excluded = fixture(emptyList(), 4)
        val first = history.canonicalize(body(excluded.client), excluded.completed)
        val posted = prefix + emitted(excluded.completed[0], emptyList()) +
            emitted(excluded.completed[2], laterNative) + emitted(excluded.completed[3], emptyList()) +
            emitted(excluded.completed[1], earlierNative) + callbacks(excluded.owner)
        assertTrue(first.bodyJson == bodyText(posted), "raw first-pass bytes must keep their posted permutation")
        val second = history.canonicalize(checkNotNull(first.body), excluded.completed)
        assertTrue(
            first.bodyJson == second.bodyJson,
            "no-capture raw callback-free history must emit identical bytes on both passes",
        )
    }

    @Test
    fun `request-local emission evidence cannot cross trees or record owners`() {
        val fixture = fixture(emptyList(), 4)
        val first = checkNotNull(history.canonicalize(body(fixture.client), fixture.completed).body)
        val root = checkNotNull(first.request).first
        val evidence = checkNotNull(first.emission)
        val changed = JsonObject(root + ("prompt_cache_key" to JsonPrimitive("synthetic changed key")))
        val foreign = fixture(emptyList(), 4).completed
        val cases = listOf(
            root to fixture.completed,
            JsonObject(root) to fixture.completed,
            changed to fixture.completed,
        )
        for ((request, records) in cases + (root to foreign)) {
            val fallback = history.canonicalize(CodeModeBody(RoundBody.Tree(request), Json), records)
            val echoed = history.canonicalize(CodeModeBody(RoundBody.Tree(request), Json, evidence), records)
            assertNull(echoed.error, "foreign emission evidence must not refuse the request")
            if (request === root && records === fixture.completed) {
                assertTrue(first.round.text == echoed.bodyJson, "the exact emitted body keeps its posted bytes")
            } else {
                assertTrue(fallback.bodyJson == echoed.bodyJson, "foreign evidence must use the original planner")
            }
        }
    }

    @Test
    fun `repeated default emissions retain bytes and cache keys across callback widths and stable anchors`() {
        val next = item("""{"role":"user","content":"synthetic newer request"}""")
        val cacheKey = JsonPrimitive("splice-synthetic-canonical-order")
        for (callbacks in listOf(0, 1, 4, 9)) {
            for (afterParent in listOf(emptyList(), listOf(next))) {
                val fixture = fixture(afterParent, callbacks)
                val root = JsonObject(mapOf("input" to JsonArray(fixture.client), "prompt_cache_key" to cacheKey))
                val first = history.canonicalize(CodeModeBody(RoundBody.Tree(root), Json), fixture.completed)
                var current = checkNotNull(first.body)
                repeat(3) {
                    val rewritten = history.canonicalize(current, fixture.completed)
                    assertTrue(first.bodyJson == rewritten.bodyJson, "each continuation must retain first-pass bytes")
                    current = checkNotNull(rewritten.body)
                    assertEquals(cacheKey, checkNotNull(current.request).first["prompt_cache_key"])
                }
            }
        }
    }

    @Test
    fun `retained duplicate-owner history has identical bytes and omissions on every pass`() {
        val fixture = fixture(emptyList(), 0)
        val later = fixture.completed[2]
        val raw = body(fixture.client + later.outer + later.outer)
        val first = history.canonicalize(raw, fixture.completed)
        assertEquals(listOf(2), first.omitted.map { fixture.completed.indexOf(it.record) })
        val emitted = checkNotNull(first.body)
        val baseline = history.canonicalize(CodeModeBody(emitted.round, Json), fixture.completed)
        assertTrue(
            first.bodyJson != baseline.bodyJson,
            "the baf214d72 planner is also non-idempotent on this reconstructed duplicate-owner history",
        )
        val second = history.canonicalize(emitted, fixture.completed)
        assertEquals(listOf(2), second.omitted.map { fixture.completed.indexOf(it.record) })
        assertTrue(first.bodyJson == second.bodyJson, "a duplicate owner must not change the next emitted request")
    }

    @Test
    fun `a completed continuation appends its tail without changing the posted prefix`() {
        for (callbacks in listOf(0, 4)) {
            val fixture = fixture(emptyList(), callbacks)
            val later = fixture.completed[2]
            val first = history.canonicalize(body(fixture.client + later.outer + later.outer), fixture.completed)
            val posted = checkNotNull(first.body)
            val prefix = input(posted)
            val completed = record(prefix, fixture.completed, "continuation", reasoning("continuation"), 0).apply {
                continuity = listOf(item("""{"role":"assistant","content":"synthetic continuation tail"}"""))
            }
            val records = fixture.completed + completed
            val isolated = history.canonicalize(
                CodeModeBody(posted.round, Json),
                listOf(completed),
                emptyMap(),
                completed,
            )
            assertTrue(isolated.omitted.isEmpty(), "the new completion must place against the actual posted body")
            assertTrue(
                bodyText(prefix) == bodyText(input(checkNotNull(isolated.body)).take(prefix.size)),
                "placing only the new completion must leave the prior posted prefix unchanged",
            )
            val appended = history.canonicalize(posted, records, emptyMap(), completed)
            val continued = checkNotNull(appended.body)
            assertTrue(appended.omitted.none { it.record === completed }, "the new completion must actually emit")
            assertTrue(input(continued).size > prefix.size, "the completion must add a real tail")
            assertTrue(
                bodyText(prefix) == bodyText(input(continued).take(prefix.size)),
                "the completed continuation must retain every byte of its already posted prefix",
            )
            val repeated = history.canonicalize(continued, records, emptyMap(), completed)
            assertTrue(appended.bodyJson == repeated.bodyJson, "the extended continuation must remain idempotent")
        }
    }

    private fun assertCaptured(fixture: Fixture) {
        val rewritten = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), fixture.owner)
        assertTrue(rewritten.omitted.isEmpty())
        assertEquals(bodyText(fixture.expected), rewritten.bodyJson)
        assertNull(history.restoreBaseline(checkNotNull(rewritten.body), fixture.owner).error)
    }

    @Test
    fun `identical captured payloads at distinct bundle witnesses retain both occurrences`() {
        assertCaptured(frozenFixture(true, true))
    }

    private fun frozenFixture(olderPostedOrder: Boolean, repeatedPayload: Boolean = false): Fixture {
        val root = prefix + (0 until 12).flatMap { reasoning("prefix-$it") }
        val parent = record(root, emptyList(), "frozen-parent", emptyList(), 4)
        val start = root + emitted(parent, emptyList())
        val earlier = record(start, listOf(parent), "frozen-earlier", earlierNative.take(1), 0)
        val first = start + emitted(earlier, earlierNative.take(1))
        val nextNative = if (repeatedPayload) earlierNative.take(1) else laterNative.take(1)
        val later = record(first, listOf(parent, earlier), "frozen-later", nextNative, 3)
        val current = first + emitted(later, nextNative)
        val addedNative = reasoning("frozen-added")
        val added = record(current, listOf(parent, earlier, later), "frozen-added", addedNative, 2)
        val captured = if (olderPostedOrder) {
            start + emitted(later, nextNative) + emitted(added, addedNative) + emitted(earlier, earlierNative.take(1))
        } else {
            current + emitted(added, emptyList())
        }
        val completed = listOf(parent, earlier, later, added)
        val source = record(captured, completed, "frozen-owner", emptyList(), 1).apply {
            phase = CodeModePhase.ACTIVE
        }
        val owner = CodeModeNativeChain.snapshot(source, setOf(source.id)).restore()
        val client = prefix + callbacks(parent) + callbacks(later) + callbacks(added) + callbacks(owner)
        val expected = captured + (if (olderPostedOrder) emptyList() else addedNative) + callbacks(owner)
        return Fixture(completed, owner, client, expected, captured)
    }

    private fun input(body: CodeModeBody): List<JsonElement> = checkNotNull(body.request).second.toList()

    private fun fixture(afterParent: List<JsonElement>, parentCallbacks: Int): Fixture {
        val parent = record(prefix, emptyList(), "parent", emptyList(), parentCallbacks)
        val parentHistory = prefix + emitted(parent, emptyList()) + afterParent
        val earlier = record(parentHistory, listOf(parent), "earlier", earlierNative, 0)
        val first = parentHistory + emitted(earlier, earlierNative)
        val later = record(first, listOf(parent, earlier), "later", laterNative, 3)
        val captured = first + emitted(later, laterNative)
        val capturedRecords = listOf(parent, earlier, later)
        val active = record(captured, capturedRecords, "owner", emptyList(), 1).apply {
            phase = CodeModePhase.ACTIVE
        }
        val owner = CodeModeNativeChain.snapshot(active, setOf(active.id)).restore()
        assertNull(owner.nativeParent)
        assertNull(owner.nativeBaseId)
        val successor = record(captured, capturedRecords, "successor", emptyList(), 2)
        val completed = capturedRecords + successor
        val client = prefix + callbacks(parent) + afterParent + callbacks(later) +
            callbacks(successor) + callbacks(owner)
        val expected = captured + emitted(successor, emptyList()) + callbacks(owner)
        return Fixture(completed, owner, client, expected, captured)
    }

    private fun record(
        items: List<JsonElement>,
        completed: List<CodeModeRecord>,
        id: String,
        native: List<JsonElement>,
        callbacks: Int,
    ): CodeModeRecord {
        val boundary = checkNotNull(history.anchoredBoundary(body(items), completed))
        val capture = CodeModeNativeChain.capture(boundary.nativeSegments, completed.lastOrNull())
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            outer = item(
                """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 'synthetic';"}""",
            ) as JsonObject,
            outerCallId = id,
            source = "return 'synthetic';",
            phase = CodeModePhase.COMPLETED,
            updatedAt = 0,
            lastDigest = "synthetic-request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = capture.segments,
            continuity = emptyList(),
            continuityReplay = if (native.isEmpty()) emptyList() else listOf(CodeModeNativeSegment(0, native)),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            record.nativeParent = capture.parent
            record.nativeBaseId = capture.parent?.id
            record.output = "synthetic result"
            repeat(callbacks) { at ->
                val clientId = "callback-$id-$at"
                record.pending += CodeModePending("runtime-$id-$at", clientId, "Read", JsonObject(emptyMap()), true)
                record.accepted.accept(mapOf(clientId to CodeModeResult(clientId, "synthetic result")), emptyMap())
            }
        }
    }

    private fun emitted(record: CodeModeRecord, native: List<JsonElement>): List<JsonElement> =
        native + record.outer + item(
            """{"type":"custom_tool_call_output","call_id":"${record.id}","output":"synthetic result"}""",
        )

    private fun callbacks(record: CodeModeRecord): List<JsonElement> = record.pending.flatMap { call ->
        listOf(
            item("""{"type":"function_call","call_id":"${call.clientId}","name":"Read","arguments":"{}"}"""),
            item("""{"type":"function_call_output","call_id":"${call.clientId}","output":"synthetic result"}"""),
        )
    }

    private fun reasoning(name: String): List<JsonElement> = (0 until 2).map { at ->
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("reasoning"),
                "id" to JsonPrimitive("synthetic-$name-$at"),
                "encrypted_content" to JsonPrimitive("synthetic encrypted $name $at"),
            ),
        )
    }

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun bodyText(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
