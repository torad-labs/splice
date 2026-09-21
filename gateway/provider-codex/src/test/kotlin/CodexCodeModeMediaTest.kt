// V4-179: a screenshot inside a splice_exec-owned tool_result reaches the model — persisted on the
// record beside the script's bounded output, replayed from there, owned by exact bytes and position.
// Each arm here is one clause of splice-astra's contract (ledger row V4-179).
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.upstream.codemode.CodeModeStep
import splice.upstream.transport.posted
import java.nio.file.Files

class CodexCodeModeMediaTest : CodeModeBridgeTestSupport() {

    @Test
    fun `a readable screenshot resumes the script and rides once after the custom output`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to IMAGE_A)

        var upstream = ""
        val outcome = manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot"), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        // The script RESUMED with the result (not interrupted), and read a marker that says where
        // the pixels went — never the base64.
        assertEquals(2, runtime.cell.advances)
        val scriptSaw = runtime.cell.results.last().single().output
        assertTrue(scriptSaw.contains("delivered to the model beside this script's output"), scriptSaw)
        assertFalse(scriptSaw.contains(IMAGE_A), "base64 must not enter the script's text")
        // On the wire: exactly one image message, immediately after the canonical custom output.
        val items = input(upstream)
        assertEquals(1, imageMessages(items).size, items.toString())
        val customOutput = items.indexOfFirst { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
        assertTrue(customOutput >= 0)
        assertEquals(turn.toolMedia.getValue(id).single(), items[customOutput + 1])
        assertFalse(items[customOutput].jsonObject.getValue("output").jsonPrimitive.content.contains(IMAGE_A))
        // Persisted beside the output, not inside it.
        val result = savedResult(id)
        assertFalse(result.getValue("output").jsonPrimitive.content.contains(IMAGE_A))
        assertEquals(turn.toolMedia.getValue(id), result.getValue("media").jsonArray.toList())
    }

    @Test
    fun `live continuation and reloaded replay emit one identical follow-up sequence`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to IMAGE_A)
        var live = ""
        manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot"), RecordingSink()) {
                live = it
                completedOutcome()
            }

        // A fresh bridge over the same state file: the next turn carries the client's own history
        // (its tool_result images rendered by the ordinary walk) plus the assistant's answer.
        val reloaded = bridge(ScriptedRuntime(ArrayDeque()))
        var replay = ""
        val next = reloaded.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot", tail = ANSWER_AND_NEXT), RecordingSink()) {
                replay = it
                completedOutcome()
            }
        assertTrue(next is TurnOutcome.Success, next.toString())
        val liveItems = input(live)
        val replayItems = input(replay)
        assertEquals(1, imageMessages(replayItems).size, replayItems.toString())
        val liveTail = liveItems.dropWhile { it.jsonObject["type"] != JsonPrimitive("custom_tool_call") }
        val replayTail = replayItems.dropWhile { it.jsonObject["type"] != JsonPrimitive("custom_tool_call") }
        assertEquals(liveTail, replayTail.take(liveTail.size), "same canonical sequence on both paths")
        assertEquals(ANSWER_AND_NEXT.size, replayTail.size - liveTail.size)
        assertTrue(logLines.none { it.contains("history rewrite skipped") }, logLines.toString())
    }

    @Test
    fun `several results keep their order across serialization`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1", "r2"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val (first, second) = sink.tools.map { it.id }
        val turn = turnWithResults(manager, first to IMAGE_A, second to IMAGE_B)
        manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, first to "one", second to "two"), RecordingSink()) { completedOutcome() }

        val reloaded = bridge(ScriptedRuntime(ArrayDeque()))
        var replay = ""
        reloaded.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, first to "one", second to "two", tail = ANSWER_AND_NEXT), RecordingSink()) {
                replay = it
                completedOutcome()
            }
        val images = imageMessages(input(replay))
        assertEquals(2, images.size)
        assertEquals(turn.toolMedia.getValue(first).single(), images[0])
        assertEquals(turn.toolMedia.getValue(second).single(), images[1])
        assertEquals(listOf(first, second), savedRecord().getValue("results").jsonObject.keys.toList())
    }

    @Test
    fun `changed media under an accepted id is a conflicting replay`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), calls("r2"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val first = start(manager, runtime)
        val accepted = turnWithResults(manager, first to IMAGE_A)
        val sink = RecordingSink()
        manager.interceptor(accepted, null, disableParallel = false)
            .intercept(history(accepted, first to "shot"), sink) { error("script still running") }
        val second = sink.tools.single().id

        // Same id, same text, DIFFERENT pixels — plus the new result the script is waiting for.
        val replayed = turnWithResults(manager, first to IMAGE_B, second to null)
        val outcome = manager.interceptor(replayed, null, disableParallel = false)
            .intercept(history(replayed, first to "shot", second to "next"), RecordingSink()) {
                error("must not post")
            }
        assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
        val message = (outcome as TurnOutcome.Failure).message
        assertTrue(message.contains("conflicting replay for code-mode tool result '$first'"), message)
        assertEquals(2, runtime.cell.advances, "the cell was not advanced on a conflicting batch")
    }

    @Test
    fun `an unrelated user image is still additional content and interrupts`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to null)
        val stray = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray { add(imagePart(IMAGE_B)) })
        }
        var upstream = ""
        val outcome = manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "text", tail = listOf(stray)), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "interrupted: the script did not resume")
        val items = input(upstream)
        assertEquals(listOf(stray), imageMessages(items), "the client's own image stays, once")
        val customOutput = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
        assertTrue(customOutput.jsonObject.getValue("output").jsonPrimitive.content.contains("unresolved"))
    }

    @Test
    fun `a legacy v3 record without captured media leaves the client's image as ordinary history`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to IMAGE_A)
        manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot"), RecordingSink()) { completedOutcome() }

        // The same file a v3 daemon would have written: no `media` key on any result. Nothing else moves.
        rewriteSavedResults { result -> JsonObject(result.filterKeys { it != "media" }) }

        val reloaded = bridge(ScriptedRuntime(ArrayDeque()))
        var replay = ""
        val next = reloaded.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot", tail = ANSWER_AND_NEXT), RecordingSink()) {
                replay = it
                completedOutcome()
            }
        assertTrue(next is TurnOutcome.Success, next.toString())
        val items = input(replay)
        // Placed (canonical pair present, no omission), and the image the client carries survives
        // exactly once as ordinary content — not claimed, not deleted, not duplicated.
        assertEquals(1, items.count { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") })
        assertEquals(1, imageMessages(items).size, items.toString())
        assertTrue(logLines.none { it.contains("history rewrite skipped") }, logLines.toString())
        // And a v3 result is unchanged: no media entry is minted for it on reload.
        assertFalse(Files.readString(tempDir.resolve("bridge.json")).contains("\"media\""))
    }

    @Test
    fun `a second script in the same turn re-canonicalizes the posted body without doubling the image`() = runTest {
        val steps = listOf(calls("r1"), CodeModeStep.Completed("done"), CodeModeStep.Completed("b-done"))
        val runtime = ScriptedRuntime(ArrayDeque(steps))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to IMAGE_A)
        val posted = mutableListOf<String>()
        val outcome = manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "shot"), RecordingSink()) { body ->
                posted += body
                // Upstream answers A's canonical history with a second script, B, at once.
                if (posted.size == 1) outerOutcome("outer-2") else completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(2, posted.size)
        val first = input(posted[0])
        val second = input(posted[1])
        // B's body is A's canonical body re-canonicalized: the durable image is still there ONCE,
        // still right after A's custom output, and B's own pair follows.
        assertEquals(1, imageMessages(second).size, second.toString())
        val aOutput = second.indexOfFirst {
            it.jsonObject["call_id"] == JsonPrimitive("outer-call") &&
                it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output")
        }
        assertEquals(turn.toolMedia.getValue(id).single(), second[aOutput + 1])
        assertEquals(2, second.count { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") })
        assertEquals(first, second.take(first.size), "A's canonical prefix is unchanged under B")
    }

    @Test
    fun `an image result the previous daemon accepted still matches after the upgrade`() = runTest {
        val (reloaded, first, second) = parkedByPreviousDaemon { manager, id ->
            turnWithResults(manager, id to IMAGE_A)
        }
        val replay = turnWithResults(reloaded, first to IMAGE_A, second to null)
        val outcome = reloaded.interceptor(replay, null, disableParallel = false)
            .intercept(history(replay, first to "shot", second to "next"), RecordingSink()) { completedOutcome() }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
    }

    @Test
    fun `a document result the previous daemon accepted still matches after the upgrade`() = runTest {
        val (reloaded, first, second) = parkedByPreviousDaemon { manager, id ->
            turnWithBlocks(manager, id to DOCUMENT)
        }
        val replay = turnWithBlocks(reloaded, first to DOCUMENT, second to TEXT_ONLY)
        val outcome = reloaded.interceptor(replay, null, disableParallel = false)
            .intercept(history(replay, first to "doc", second to "next"), RecordingSink()) { completedOutcome() }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
    }

    @Test
    fun `a legacy result replayed with different text is still a conflict`() = runTest {
        val (reloaded, first, second) = parkedByPreviousDaemon { manager, id ->
            turnWithResults(manager, id to IMAGE_A)
        }
        val changed = """{"type":"text","text":"changed"},${imageBlock(IMAGE_A)}"""
        val replay = turnWithBlocks(reloaded, first to changed, second to TEXT_ONLY)
        val outcome = reloaded.interceptor(replay, null, disableParallel = false)
            .intercept(history(replay, first to "shot", second to "next"), RecordingSink()) { error("must not post") }
        assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
        val message = (outcome as TurnOutcome.Failure).message
        assertTrue(message.contains("conflicting replay for code-mode tool result '$first'"), message)
    }

    @Test
    fun `a completed record replayed with different media is omitted, not mixed with the captured image`() = runTest {
        val (manager, id) = completedWith(IMAGE_A)
        // Same id, same text, equal-length base64 (the marker text is identical too): B is keyed to r1
        // from inside the same tool_result, so this is not a user image — it is a different result.
        val replay = turnWithResults(manager, id to IMAGE_B)
        var posted = ""
        val outcome = manager.interceptor(replay, null, disableParallel = false)
            .intercept(history(replay, id to "shot", tail = ANSWER_AND_NEXT), RecordingSink()) {
                posted = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        val items = input(posted)
        // Coherent fallback: the record is omitted (no canonical pair, no captured A), and the client's
        // ordinary callback with B rides exactly as the client sent it.
        assertEquals(0, items.count { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") })
        assertEquals(listOf(replay.toolMedia.getValue(id).single()), imageMessages(items))
        assertEquals(1, items.count { it.jsonObject["type"] == JsonPrimitive("function_call_output") })
        val skipped = logLines.any { it.contains("history rewrite skipped") && it.contains("differ") }
        assertTrue(skipped, logLines.toString())
    }

    @Test
    fun `a captured-empty result replayed with an image is omitted the same way`() = runTest {
        val (manager, id) = completedWith(null)
        val replay = turnWithResults(manager, id to IMAGE_B)
        var posted = ""
        manager.interceptor(replay, null, disableParallel = false)
            .intercept(history(replay, id to "t", tail = ANSWER_AND_NEXT), RecordingSink()) {
                posted = it
                completedOutcome()
            }
        val items = input(posted)
        assertEquals(0, items.count { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") })
        assertEquals(listOf(replay.toolMedia.getValue(id).single()), imageMessages(items))
    }

    // ---- harness ----

    /** One script that accepted r1 ([image] or text-only) and completed. Returns the bridge and r1's id. */
    private suspend fun completedWith(image: String?): Pair<CodexCodeModeBridge, String> {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val id = start(manager, runtime)
        val turn = turnWithResults(manager, id to image)
        val outcome = manager.interceptor(turn, null, disableParallel = false)
            .intercept(history(turn, id to "t"), RecordingSink()) { completedOutcome() }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        return manager to id
    }

    private suspend fun start(manager: CodexCodeModeBridge, runtime: ScriptedRuntime): String {
        val sink = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        assertEquals(1, runtime.starts)
        return sink.tools.single().id
    }

    private fun calls(vararg ids: String) = CodeModeStep.Calls(ids.map { call(it, "Read") })

    /** The Turn production would build: results AND media from the client's Anthropic body, through
     *  the same builder and the same renderer. [image] null means a text-only result. */
    private fun turnWithResults(
        manager: CodexCodeModeBridge,
        vararg results: Pair<String, String?>,
    ): CodexCodeModeBridge.Turn = turnWithBlocks(
        manager,
        *results.map { (id, image) -> id to (TEXT_ONLY + image?.let { "," + imageBlock(it) }.orEmpty()) }.toTypedArray(),
    )

    /** [blocks]: each result id to the raw Anthropic content parts of its tool_result. */
    private fun turnWithBlocks(
        manager: CodexCodeModeBridge,
        vararg blocks: Pair<String, String>,
    ): CodexCodeModeBridge.Turn {
        val results = blocks.joinToString(",") { (id, parts) ->
            """{"type":"tool_result","tool_use_id":"$id","content":[$parts]}"""
        }
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],""" +
                """"messages":[{"role":"user","content":[$results]}]}""",
        )
        val builder = CodexCodeModeTurnBuilder(manager, media())
        return turn(results = builder.toolResults(body))
            .copy(toolMedia = builder.toolMedia(body), legacyResults = builder.legacyResults(body))
    }

    /** A script parked by the daemon BEFORE V4-179: its first result (the turn [accept] builds for
     *  that result's id) was accepted and persisted with that daemon's marker text and no media, the
     *  script then asked for a second call, and the daemon restarted (the record reloads LOST).
     *  Returns the reloaded bridge and the two client ids. */
    private suspend fun parkedByPreviousDaemon(
        accept: (CodexCodeModeBridge, String) -> CodexCodeModeBridge.Turn,
    ): Triple<CodexCodeModeBridge, String, String> {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(calls("r1"), calls("r2"))))
        val manager = bridge(runtime)
        val first = start(manager, runtime)
        val accepted = accept(manager, first)
        val sink = RecordingSink()
        manager.interceptor(accepted, null, disableParallel = false)
            .intercept(history(accepted, first to "one"), sink) { error("script still running") }
        val second = sink.tools.single().id
        // What that daemon wrote: the V4-178 marker as the result's text, and no media key.
        val legacyText = JsonPrimitive(accepted.legacyResults.single().output)
        rewriteSavedResults { result -> JsonObject(result.filterKeys { it != "media" } + ("output" to legacyText)) }
        return Triple(bridge(ScriptedRuntime(ArrayDeque())), first, second)
    }

    private fun rewriteSavedResults(rewrite: (JsonObject) -> JsonObject) {
        val file = tempDir.resolve("bridge.json")
        val state = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val records = state.getValue("records").jsonArray.map { record ->
            val results = record.jsonObject.getValue("results").jsonObject.mapValues { (_, r) -> rewrite(r.jsonObject) }
            JsonObject(record.jsonObject + ("results" to JsonObject(results)))
        }
        Files.writeString(file, JsonObject(state + ("records" to JsonArray(records))).toString())
    }

    private fun savedResult(id: String): JsonObject =
        savedRecord().getValue("results").jsonObject.getValue(id).jsonObject

    private fun imageBlock(data: String): String =
        """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}"""

    /** The Responses input the ordinary walk renders for that history: each pair, then the images
     *  the walk rides after its function_call_output — the very items the turn's media holds. */
    private fun history(
        turn: CodexCodeModeBridge.Turn,
        vararg outputs: Pair<String, String>,
        tail: List<JsonElement> = emptyList(),
    ): String {
        val items = buildList {
            add(roleText("developer", "s"))
            outputs.forEach { (id, output) ->
                add(
                    buildJsonObject {
                        put("type", "function_call")
                        put("call_id", id)
                        put("name", "Read")
                        put("arguments", "{}")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", id)
                        put("output", output)
                    },
                )
                addAll(turn.toolMedia[id].orEmpty())
            }
            addAll(tail)
        }
        return buildJsonObject { put("input", JsonArray(items)) }.toString()
    }

    private fun input(body: String): List<JsonElement> =
        Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.toList()

    private fun imageMessages(items: List<JsonElement>): List<JsonElement> = items.filter { item ->
        val content = item.jsonObject["content"] as? JsonArray ?: return@filter false
        content.any { it.jsonObject["type"] == JsonPrimitive("input_image") }
    }

    private fun imagePart(data: String): JsonObject = buildJsonObject {
        put("type", "input_image")
        put("image_url", "data:image/png;base64,$data")
    }

    private fun savedRecord(): JsonObject = Json.parseToJsonElement(Files.readString(tempDir.resolve("bridge.json")))
        .jsonObject.getValue("records").jsonArray.single().jsonObject
}

private const val IMAGE_A = "AAAA"
private const val IMAGE_B = "AQID"
private const val TEXT_ONLY = """{"type":"text","text":"t"}"""
private const val DOCUMENT = """{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"AAAA"}}"""
private fun roleText(role: String, text: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", text)
}

private val ANSWER_AND_NEXT: List<JsonElement> = listOf(roleText("assistant", "done"), roleText("user", "next"))
