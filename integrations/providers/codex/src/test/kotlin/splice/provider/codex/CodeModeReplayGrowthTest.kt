// NEW: a code-mode conversation's upstream body stays proportional to the client's. On
// 2026-10-04 one claudex session's posts went 5.4, 10.2, 19.6 and 37.9 MB from a 0.75 MB client body:
// its persisted records held one reasoning item 1, 2, 4 ... 1024 times at the same slot. Each script's
// record is captured from the body splice posted, and a capture whose slots no longer line up with its
// predecessor's is a root holding the whole native history, the reasoning the earlier records emit as
// their own continuity included. Every owner re-emitted its copy at the shared slot, so each post
// carried the reasoning twice as often as the one before, and the next root captured that.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.state.CodeModeExtraContent
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.native.CodeModeAnchorCapture
import splice.upstream.codemode.CodeModeResult

private const val SCRIPTS = 6

internal class CodeModeReplayGrowthTest : CodeModeBridgeTestSupport() {
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val history = listOf(
        element("""{"role":"developer","content":"s"}"""),
        element("""{"role":"user","content":"Fix it."}"""),
    )

    @Test
    fun `scripts captured from splice's own posts keep each reasoning item once`() {
        var client = history + callback("call-0")
        val records = mutableListOf(record("script-0", history, "call-0", emptyList()))
        val posted = mutableListOf<String>()
        repeat(SCRIPTS) { n ->
            val post = rewrite(client, records)
            posted += post
            // The next script starts in that post, so its record is captured from it. Its slots no longer
            // line up with its predecessor's, so it is a root: it holds the whole native history.
            records += record("script-${n + 1}", items(post), "call-${n + 1}", records.toList())
            client = client + callback("call-${n + 1}")
        }
        val reasoning = posted.map { post ->
            items(post).filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        }
        val counts = reasoning.map { it.size }
        reasoning.forEachIndexed { n, items ->
            assertEquals(items.distinct(), items, "post $n carries each reasoning item once; per post: $counts")
            assertEquals(n + 1, items.size, "post $n carries one reasoning item per script; per post: $counts")
        }
        val growth = posted.zipWithNext { a, b -> b.length - a.length }
        assertTrue(growth.distinct().size == 1, "each script adds the same bytes to the post: $growth")
    }

    /** The roots captured before the rewrite emitted each item once stored the copies, and a journal load now
     *  drops them (CodeModeNativeCopies). What the conversation posts does not change. */
    @Test
    fun `records reloaded without their stored copies post the body they posted with them`() {
        var client = history + callback("call-0")
        val records = mutableListOf(record("script-0", history, "call-0", emptyList()))
        repeat(SCRIPTS) { n ->
            records += record("script-${n + 1}", items(rewrite(client, records)), "call-${n + 1}", records.toList())
            client = client + callback("call-${n + 1}")
        }
        records.forEach { record ->
            record.nativeSegments = record.nativeSegments.map { it.copy(items = it.items + it.items) }
        }
        val before = rewrite(client, records)
        store().also { it.load() }.save(records, emptyList())

        val reloaded = store().load().records.map { it.restore() }.also(CodeModeNativeChain::link)

        assertEquals(natives(records) / 2, natives(reloaded), "the reload keeps each stored item once")
        assertEquals(records.size, reasoningIn(before).size, "the post carries every script's reasoning")
        assertEquals(before, rewrite(client, reloaded))
    }

    /** The witness is what makes the client's echo of a step owned rather than foreign content. A restart between
     *  a Calls step and the client's continuation reloads the record from disk, so a witness that is not saved is
     *  a round splice cuts after every restart. Both states are proven from the same reload: the saved witness
     *  places the echo, and a step that captured none places nothing. */
    @Test
    fun `an issued step's delivered witness survives a restart and still owns the client's echo`() {
        val witness = "Reading the rate limiter."
        val carried = CodeModeRecords.of("key", 1).apply {
            issued += CodeModeIssuedStep(lastDigest, emptyList(), witness)
        }
        val legacy = CodeModeRecords.of("key", 2).apply { issued += CodeModeIssuedStep(lastDigest, emptyList()) }
        store().also { it.load() }.save(listOf(carried, legacy), emptyList())

        // From the FILE, so this proves the save carries the witness rather than something rebuilding it.
        val steps = stateFiles.records().flatMap { it.getValue("issued").jsonArray.map { step -> step.jsonObject } }
        assertEquals(
            listOf(witness),
            steps.mapNotNull { it["deliveredText"]?.jsonPrimitive?.content },
            "the saved bytes hold the captured witness and no key for the step without one: $steps",
        )

        val reloaded = store().load().records.map { it.restore() }.associateBy { it.id }
        val echo = listOf(ResponsesAssistantText.item(witness, AssistantPhase.COMMENTARY))
        val placement = CodeModeExtraContent(codec, CodeModeOwnership(codec))
        val after = checkNotNull(reloaded[carried.id])
        assertEquals(
            listOf(witness),
            after.issued.map { it.deliveredText },
            "the reloaded step carries the prose the client was shown",
        )
        assertEquals(setOf(0), placement.indexes(echo, 0, after), "the echo is owned after a restart")
        assertEquals(
            emptySet<Int>(),
            placement.indexes(echo, 0, checkNotNull(reloaded[legacy.id])),
            "a step that captured no witness owns no echo",
        )
    }

    /** The native items a step was sent (the reasoning delivered before its callback) are owned as replay. On a held
     *  source the client's echo carries typed context between that reasoning and the owned callback, so projection
     *  files the reasoning as a native segment of its own, and only the saved witness tells it apart from foreign
     *  content after a restart. Both states come from one reload: the captured step owns that segment, and a step
     *  that captured none owns nothing. */
    @Test
    fun `an issued step's delivered native items survive a restart and still own the client's echo`() {
        val native = listOf(reasoning("rs-synthetic-delivered"))
        val carried = record("script-carried", history, "call-0", emptyList()).also { record ->
            record.issued[0] = record.issued[0].copy(deliveredNative = native)
        }
        val legacy = record("script-legacy", history, "call-0", emptyList())
        store().also { it.load() }.save(listOf(carried, legacy), emptyList())

        // From the FILE, so this proves the save carries the items rather than something rebuilding them.
        val steps = stateFiles.records().flatMap { it.getValue("issued").jsonArray.map { step -> step.jsonObject } }
        assertEquals(
            listOf(JsonArray(native)),
            steps.mapNotNull { it["deliveredNative"] },
            "the saved bytes hold the captured items and no key for the step without them: $steps",
        )

        val reloaded = store().load().records.map { it.restore() }.associateBy { it.id }
        val after = checkNotNull(reloaded[carried.id])
        assertEquals(listOf(native), after.issued.map { it.deliveredNative }, "the reloaded step carries the items")

        val echo = history + native + ResponsesContextMessage.item("synthetic context") + callback("call-0")
        val projected = ResponsesCodeModeProjection().project(JsonArray(echo))
        val placement = CodeModeExtraContent(codec, CodeModeOwnership(codec))
        assertEquals(
            listOf(native),
            placement.deliveredReplay(projected, after).map { it.items },
            "the echoed reasoning is owned after a restart: ${projected.replayItems}",
        )
        assertEquals(
            emptySet<ResponsesCodeModeReplay>(),
            placement.deliveredReplay(projected, checkNotNull(reloaded[legacy.id])),
            "a step that captured no native items owns no echo",
        )
    }

    private fun store() = CodexCodeModeStore(stateLocation(), Json { encodeDefaults = true }, {})

    private fun natives(records: List<CodeModeRecord>): Int = records.sumOf { record ->
        record.nativeSegments.sumOf { it.items.size }
    }

    private fun reasoningIn(post: String): List<JsonElement> =
        items(post).filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }

    /** A completed script started on [baseline]: one client callback, and its reasoning before the call. */
    private fun record(
        id: String,
        baseline: List<JsonElement>,
        callback: String,
        completed: List<CodeModeRecord>,
    ): CodeModeRecord {
        val boundary = checkNotNull(CodeModeAnchorCapture.inputBoundary(body(baseline), completed, codec))
        return CodeModeRecord(
            id = id,
            key = "key",
            outer = outer(id).raw,
            outerCallId = id,
            source = "source",
            phase = CodeModePhase.COMPLETED,
            output = "done",
            updatedAt = 1_000,
            lastDigest = "request",
            baselineInputCount = boundary.fullCount,
            baselineInputDigest = "",
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = boundary.logicalCount,
            baselineLogicalDigest = "",
            nativeSegments = boundary.nativeSegments,
            continuity = emptyList(),
            continuityReplay = listOf(CodeModeNativeSegment(0, listOf(reasoning("rs-$id")))),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            record.accepted.accept(mapOf(callback to CodeModeResult(callback, "result")), emptyMap())
            record.issued += CodeModeIssuedStep(
                "request",
                listOf(CodeModePending("runtime", callback, "Read", JsonObject(emptyMap()), true)),
            )
        }
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        element("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        element("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun reasoning(id: String) =
        element("""{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"e"}""")

    private fun rewrite(input: List<JsonElement>, records: List<CodeModeRecord>): String {
        val result = CodexCodeModeHistory(Json).canonicalize(body(input), records)
        assertEquals(emptyList<String>(), result.omitted.map { it.reason }, "every record places")
        return checkNotNull(result.bodyJson)
    }

    private fun items(body: String): List<JsonElement> = checkNotNull(codec.root(body)).second
    private fun body(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()
    private fun element(text: String): JsonElement = Json.parseToJsonElement(text)
}
