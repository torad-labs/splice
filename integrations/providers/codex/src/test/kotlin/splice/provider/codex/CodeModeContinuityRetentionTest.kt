// NEW: a disqualified captured-input carrier retains only its independently owned response continuity.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult

internal class CodeModeContinuityRetentionTest {
    private val history = CodexCodeModeHistory(Json)
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val user = item("""{"role":"user","content":"synthetic opening"}""")
    private val repeated =
        item(
            """{"type":"reasoning","id":"synthetic-repeat","encrypted_content":"synthetic repeat"}""",
        )
    private val between =
        item(
            """{"type":"reasoning","id":"synthetic-between","encrypted_content":"synthetic between"}""",
        )
    private val response =
        item(
            """{"type":"reasoning","id":"synthetic-response","encrypted_content":"synthetic response"}""",
        )

    private data class Fixture(
        val completed: List<CodeModeRecord>,
        val carrier: CodeModeRecord,
        val target: CodeModeRecord,
        val client: List<JsonElement>,
    )

    @Test
    fun `an omitted carrier outside selected ancestry keeps its own native response`() {
        val fixture = fixture(true)
        assertNull(fixture.target.nativeParent)
        assertNull(fixture.target.nativeBaseId)
        for (capture in listOf(null, fixture.target)) {
            val rewrite = history.canonicalize(body(fixture.client), fixture.completed, emptyMap(), capture)
            val omission = rewrite.omitted.single { it.record.id == fixture.carrier.id }
            assertNotNull(omission.nativeRejection)
            val rewritten = checkNotNull(rewrite.body)
            val projection = codec.conversation(codec.projection.project(checkNotNull(rewritten.request).second)).body
            assertEquals(listOf(5), projection.replayItems.filter { response in it.items }.map { it.logicalOffset })
            if (capture != null) {
                val restored = history.restoreBaseline(rewritten, fixture.target)
                assertNull(
                    restored.error,
                    "expected=" + CodeModeNativeChain.replay(fixture.target).map { it.logicalOffset to it.items.size } +
                        " actual=" + projection.replayItems.map { it.logicalOffset to it.items.size } +
                        " rejection=" + restored.nativeRejection,
                )
            }
            assertFalse(input(rewritten).any { codec.callId(it) == fixture.carrier.outerCallId })
            assertTrue(input(rewritten).containsAll(fixture.carrier.continuity))
        }
    }

    @Test
    fun `default omission adds only own response and never disqualified baseline natives`() {
        val fixture = fixture()
        val rewrite = history.canonicalize(body(fixture.client), fixture.completed)
        val expected = listOf(user) + fixture.completed.take(2).flatMap(::emitted) +
            response + callbacks(fixture.target)
        assertEquals(checkNotNull(body(expected).request).first.toString(), rewrite.bodyJson)
        assertFalse(input(checkNotNull(rewrite.body)).any { it == repeated || it == between })
        val second = history.canonicalize(checkNotNull(rewrite.body), fixture.completed)
        assertEquals(rewrite.bodyJson, second.bodyJson, "retained response bytes are stable on the next pass")
    }

    @Test
    fun `retention keeps ordinary callback results and durable media`() {
        val fixture = fixture(true, 1)
        val callback = fixture.carrier.pending.single().clientId
        val media = item("""{"role":"user","content":"synthetic durable media"}""")
        fixture.carrier.accepted.accept(
            mapOf(callback to CodeModeResult(callback, "synthetic result")),
            mapOf(callback to listOf(media)),
        )
        val at = fixture.client.indexOf(callbacks(fixture.carrier).last()) + 1
        val client = fixture.client.take(at) + media + fixture.client.drop(at)
        val rewrite = history.canonicalize(body(client), fixture.completed, mapOf(callback to listOf(media)))
        val actual = input(checkNotNull(rewrite.body))
        assertTrue(actual.containsAll(callbacks(fixture.carrier)))
        assertTrue(media in actual)
        assertTrue(actual.containsAll(fixture.carrier.continuity))
        assertTrue(response in actual)
        assertFalse(actual.any { codec.callId(it) == fixture.carrier.outerCallId })
        assertEquals(rewrite.bodyJson, history.canonicalize(checkNotNull(rewrite.body), fixture.completed).bodyJson)
    }

    @Test
    fun `ambiguous callback identity and changed media do not qualify a response carrier`() {
        val fixture = fixture(true, 1)
        val call = callbacks(fixture.carrier).first()
        val duplicate = history.canonicalize(body(fixture.client + call), fixture.completed)
        assertFalse(response in input(checkNotNull(duplicate.body)))
        val callback = fixture.carrier.pending.single().clientId
        fixture.carrier.accepted.accept(
            mapOf(callback to CodeModeResult(callback, "synthetic result")),
            mapOf(callback to listOf(user)),
        )
        val changed = history.canonicalize(body(fixture.client), fixture.completed, mapOf(callback to listOf(between)))
        assertFalse(response in input(checkNotNull(changed.body)))
    }

    @Test
    fun `a forged captured native payload cannot be healed by retaining a response`() {
        val fixture = fixture(true)
        val forged = JsonObject((repeated as JsonObject) + ("encrypted_content" to JsonPrimitive("synthetic edited")))
        val client = fixture.client.map { if (it == repeated) forged else it }
        val rewritten = history.canonicalize(body(client), fixture.completed, emptyMap(), fixture.target)
        assertNotNull(history.restoreBaseline(checkNotNull(rewritten.body), fixture.target).error)
    }

    @Test
    fun `a rejected capture leaves an ordinary record outside its native span canonical`() {
        val fixture = fixture(true)
        val next = item("""{"role":"user","content":"synthetic independent request"}""")
        val ordinary = record(listOf(next), emptyList(), "ordinary", emptyList(), 1)
        val forged = JsonObject((repeated as JsonObject) + ("encrypted_content" to JsonPrimitive("synthetic edited")))
        val client = fixture.client.map { if (it == repeated) forged else it } + next + callbacks(ordinary)
        val rewrite = history.canonicalize(body(client), fixture.completed + ordinary, emptyMap(), fixture.target)
        val ordinaryDefault = history.canonicalize(body(listOf(next) + callbacks(ordinary)), listOf(ordinary))
        val expected = input(checkNotNull(ordinaryDefault.body))
        assertEquals(expected, input(checkNotNull(rewrite.body)).takeLast(expected.size))
        assertNotNull(history.restoreBaseline(checkNotNull(rewrite.body), fixture.target).error)
    }

    @Test
    fun `a rejected incomplete ancestry remains counted with no restored body`() {
        val fixture = fixture(true)
        fixture.target.nativeBaseId = "synthetic-missing-parent"
        val client = listOf(user, repeated) + emitted(fixture.completed[0]) + listOf(repeated, between) +
            emitted(fixture.completed[1]) + response + fixture.carrier.continuity + callbacks(fixture.target)
        val rewrite = history.canonicalize(body(client), fixture.completed, emptyMap(), fixture.target)
        val restored = history.restoreBaseline(checkNotNull(rewrite.body), fixture.target)
        assertEquals(CodeModeNativeBranch.COUNT, restored.nativeRejection?.branch)
        assertNull(restored.body)
    }

    private fun fixture(withCommentary: Boolean = false, carrierCallbacks: Int = 0): Fixture {
        val commentary = if (withCommentary) {
            listOf(item("""{"role":"assistant","content":"synthetic commentary","phase":"commentary"}"""))
        } else {
            emptyList()
        }
        val first = record(listOf(user), emptyList(), "first", emptyList(), 1)
        val prefix = listOf(user) + emitted(first)
        val second = record(prefix, listOf(first), "second", emptyList(), 1)
        val captured = listOf(user, repeated) + emitted(first) + listOf(between, repeated) + emitted(second)
        val carrier = record(captured, listOf(first, second), "carrier", listOf(response), carrierCallbacks, commentary)
        val completed = listOf(first, second, carrier)
        val source = captured + response + commentary + emitted(carrier)
        val linked = record(source, completed, "target", emptyList(), 1).apply { phase = CodeModePhase.ACTIVE }
        val target = CodeModeNativeChain.snapshot(linked, setOf(linked.id)).restore()
        val client = listOf(user, repeated) + callbacks(first) + between + callbacks(second) +
            callbacks(carrier) + callbacks(target)
        return Fixture(completed, carrier, target, client)
    }

    private fun record(
        input: List<JsonElement>,
        completed: List<CodeModeRecord>,
        id: String,
        native: List<JsonElement>,
        callbacks: Int,
        continuity: List<JsonElement> = emptyList(),
    ): CodeModeRecord {
        val boundary = checkNotNull(history.anchoredBoundary(body(input), completed))
        val capture = CodeModeNativeChain.capture(boundary.nativeSegments, completed.lastOrNull())
        return CodeModeRecord(
            id = id,
            key = "synthetic-retention",
            outer = item(
                """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 1"}""",
            ) as JsonObject,
            outerCallId = id,
            source = "return 1",
            phase = CodeModePhase.COMPLETED,
            updatedAt = 0,
            lastDigest = "synthetic-request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = boundary.fullDigest,
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = boundary.logicalDigest,
            nativeSegments = capture.segments,
            continuity = continuity,
            continuityReplay = native.takeIf(List<JsonElement>::isNotEmpty)?.let {
                listOf(CodeModeNativeSegment(0, it))
            }.orEmpty(),
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

    private fun emitted(record: CodeModeRecord): List<JsonElement> =
        listOf(CodeModeCallReplay.item(record), codec.customOutput(record))

    private fun callbacks(record: CodeModeRecord): List<JsonElement> = record.pending.flatMap { call ->
        listOf(
            item("""{"type":"function_call","call_id":"${call.clientId}","name":"Read","arguments":"{}"}"""),
            item("""{"type":"function_call_output","call_id":"${call.clientId}","output":"synthetic result"}"""),
        )
    }

    private fun input(body: CodeModeBody): List<JsonElement> = checkNotNull(body.request).second.toList()

    private fun body(input: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(input)))), Json)

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
