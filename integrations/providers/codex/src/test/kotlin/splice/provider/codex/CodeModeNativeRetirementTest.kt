// NEW: completed native rejection retires input lineage, never callback authentication or response bytes.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeSourceUsage
import splice.upstream.codemode.CodeModeStep

internal class CodeModeNativeRetirementTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a permanently rejected completed native cannot reclaim restored input`() = runTest {
        val fixture = completedNative()
        try {
            rejectCompletedNative(fixture)
            val restored = nativeResult(fixture.readId) + Json.parseToJsonElement(
                """{"role":"user","content":"synthetic later continuation"}""",
            )
            var posted = ""
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(restored), RecordingSink()) { request ->
                    posted = request
                    completedOutcome()
                }
            val actual = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
            assertTrue(
                actual.containsAll(ownedCallbacks(fixture.readId)),
                "restored native bytes cannot let a rejected completed record rewrite its old callbacks",
            )
            assertTrue(fixture.registry.completed(fixture.record.key).isEmpty())
            assertTrue(fixture.record.nativeSegments.isEmpty(), "rejected input lineage is no longer retained")
            assertEquals(null, fixture.record.nativeParent)
            assertEquals(null, fixture.record.nativeBaseId)
            assertEquals(2, fixture.runtime.cell.advances, "completed source was not rerun")
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `retired completed natives preserve owned later callbacks without owning unrelated calls`() = runTest {
        val fixture = completedNative()
        try {
            rejectCompletedNative(fixture)
            val unrelated = listOf(
                Json.parseToJsonElement(
                    """{"type":"function_call","call_id":"synthetic-unowned","name":"Read","arguments":"{}"}""",
                ),
                Json.parseToJsonElement(
                    """{"type":"function_call_output","call_id":"synthetic-unowned","output":"synthetic unrelated result"}""",
                ),
            )
            var posted = ""
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(nativeResult(fixture.readId) + unrelated), RecordingSink()) { request ->
                    posted = request
                    completedOutcome()
                }
            val actual = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
            assertTrue(
                actual.containsAll(ownedCallbacks(fixture.readId)),
                "authenticated callback history stays ordinary",
            )
            assertTrue(actual.containsAll(unrelated), "unissued calls cannot become owned history")
            val replayed = RecordingSink()
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(baselineWithNativeSearch(), replayed) {
                    error("an authenticated issued step replays locally")
                }
            assertEquals(listOf(fixture.readId), replayed.tools.map { it.id })
            assertEquals(2, fixture.runtime.cell.advances, "issued-step replay never reruns the source")
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `native retirement preserves rejecting request bytes and its prompt cache key`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) { record ->
                record.continuity = listOf(
                    Json.parseToJsonElement("""{"role":"assistant","content":"synthetic owned commentary"}"""),
                )
                record.continuityReplay = listOf(
                    CodeModeNativeSegment(
                        0,
                        listOf(
                            Json.parseToJsonElement(
                                """{"type":"reasoning","id":"synthetic-response","encrypted_content":"synthetic response"}""",
                            ),
                        ),
                    ),
                )
            }
            val before = rejectCompletedNative(fixture)
            val after = rejectCompletedNative(fixture)
            assertTrue(before == after, "retirement cannot change emitted request bytes")
            assertEquals(
                "splice-synthetic-native-retirement",
                Json.parseToJsonElement(after).jsonObject.getValue("prompt_cache_key").jsonPrimitive.content,
            )
            assertTrue(fixture.record.nativeSegments.isEmpty(), "the native input was retired")
            assertFalse(fixture.record.abandoned(), "retirement is not abandonment")
            assertFalse(logLines.any { "abandoned record" in it })
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `retirement survives reload and authenticates issued replay without restarting source`() = runTest {
        val fixture = completedNative()
        rejectCompletedNative(fixture)
        fixture.manager.onHeadStop()
        val freshRuntime = ScriptedRuntime(ArrayDeque())
        val fresh = bridge(freshRuntime)
        try {
            val key = fixture.record.key
            val restored = registry(fresh).recordsFor(key).single()
            assertTrue(registry(fresh).completed(key).isEmpty())
            assertTrue(restored.nativeSegments.isEmpty())
            assertEquals(null, restored.nativeParent)
            assertEquals(null, restored.nativeBaseId)
            assertFalse(restored.abandoned())
            assertEquals(CodeModePhase.COMPLETED, restored.phase)
            val replayed = RecordingSink()
            fresh.interceptor(turn(), null, disableParallel = false)
                .intercept(baselineWithNativeSearch(), replayed) { error("authenticated replay is local after reload") }
            assertEquals(listOf(fixture.readId), replayed.tools.map { it.id })
            assertEquals(0, freshRuntime.cell.advances)
            assertFalse(logLines.any { "abandoned record" in it })
        } finally {
            fresh.onHeadStop()
        }
    }

    @Test
    fun `missing callback history does not retire a completed native carrier`() = runTest {
        val fixture = completedNative()
        try {
            fixture.manager.interceptor(turn(), null, disableParallel = false)
                .intercept(nativeRequest(emptyList()), RecordingSink()) { completedOutcome() }
            assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
            assertTrue(fixture.record.nativeSegments.isNotEmpty())
            assertEquals(null, fixture.record.error)
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `native retirement clears completed descendants only in its conversation`() = runTest {
        val fixture = completedNative()
        try {
            val child = fixture.record.snapshot().copy(id = "synthetic-descendant").restore().apply {
                replayAnchors = fixture.record.replayAnchors
                nativeBaseId = fixture.record.id
                nativeParent = fixture.record
            }
            val other = fixture.record.snapshot().copy(
                id = "synthetic-other",
                key = "synthetic-other-conversation",
            ).restore().apply { replayAnchors = fixture.record.replayAnchors }
            assertTrue(fixture.registry.add(child))
            assertTrue(fixture.registry.add(other))
            rejectCompletedNative(fixture)
            assertTrue(fixture.registry.completed(fixture.record.key).isEmpty())
            assertTrue(child.nativeSegments.isEmpty())
            assertEquals(null, child.nativeParent)
            assertEquals(null, child.nativeBaseId)
            assertEquals(listOf(other), fixture.registry.completed(other.key))
            assertTrue(other.nativeSegments.isNotEmpty())
            assertEquals(null, other.error)
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `a completed cell cannot retire its still running upstream source`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) { it.sourceState = CodeModeSourceState(complete = false) }
            rejectCompletedNative(fixture)
            assertEquals(null, fixture.record.error, "an unfinished source must still accept terminal usage")
            assertTrue(fixture.record.nativeSegments.isNotEmpty())
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `unconsumed source billing survives retirement until its durable claim`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) {
                it.sourceState = CodeModeSourceState(
                    complete = true,
                    usage = CodeModeSourceUsage(10, 2, 0, 0, 0),
                )
            }
            val rejected = CodeModeOmission(
                fixture.record,
                CodeModeNativeBranch.PAYLOAD.reason(),
                CodeModeNativeRejection(true, CodeModeNativeBranch.PAYLOAD),
            )
            fixture.registry.changes.retireNative(listOf(rejected))
            assertEquals(null, fixture.record.error)
            assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
            assertEquals(10L, fixture.registry.source.consume(fixture.record)?.inputTokens)
            fixture.registry.changes.retireNative(listOf(rejected))
            assertTrue(fixture.registry.completed(fixture.record.key).isEmpty())
            assertEquals(null, fixture.registry.source.consume(fixture.record))
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `missing native evidence and partial ancestry cannot make a permanent retirement`() = runTest {
        val fixture = completedNative()
        try {
            val branches = listOf(
                CodeModeNativeBranch.ABSENT,
                CodeModeNativeBranch.COUNT,
                CodeModeNativeBranch.UNEXPECTED,
            )
            for (branch in branches) {
                val omission = CodeModeOmission(fixture.record, branch.reason(), CodeModeNativeRejection(null, branch))
                fixture.registry.changes.retireNative(listOf(omission))
                assertEquals(null, fixture.record.error)
                assertEquals(listOf(fixture.record), fixture.registry.completed(fixture.record.key))
                assertTrue(fixture.record.nativeSegments.isNotEmpty())
            }
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    @Test
    fun `completed abandoned source carriers remain visible until their terminal bill is claimed`() = runTest {
        val fixture = completedNative()
        try {
            fixture.registry.changes.save(fixture.record) {
                it.error = CODE_MODE_ABANDONED
                it.sourceState = CodeModeSourceState(
                    complete = true,
                    usage = CodeModeSourceUsage(10, 2, 0, 0, 0),
                )
            }
            assertTrue(
                fixture.record in fixture.registry.completed(fixture.record.key),
                "a completed abandon carrier remains visible for terminal billing",
            )
            assertEquals(10L, fixture.registry.source.consume(fixture.record)?.inputTokens)
            assertEquals(null, fixture.registry.source.consume(fixture.record))
        } finally {
            fixture.manager.onHeadStop()
        }
    }

    private data class CompletedNative(
        val runtime: ScriptedRuntime,
        val manager: CodexCodeModeBridge,
        val registry: CodexCodeModeRegistry,
        val readId: String,
        val record: CodeModeRecord,
    )

    private suspend fun completedNative(): CompletedNative {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(CodeModeStep.Calls(listOf(call("synthetic-read", "Read"))), CodeModeStep.Completed("done")),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(baselineWithNativeSearch(), sink) { outerOutcome() }
        val readId = sink.tools.single().id
        manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(nativeRequest(nativeResult(readId)), RecordingSink()) { completedOutcome() }
        val registry = registry(manager)
        val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
        val record = registry.recordsFor(key).single()
        assertEquals(CodeModePhase.COMPLETED, record.phase, "the control must reach completed native state")
        assertTrue(record.nativeSegments.isNotEmpty())
        return CompletedNative(runtime, manager, registry, readId, record)
    }

    private suspend fun rejectCompletedNative(fixture: CompletedNative): String {
        val changed = nativeResult(fixture.readId).map { item ->
            if (item.jsonObject["type"]?.jsonPrimitive?.content == "tool_search_call") {
                JsonObject(item.jsonObject + ("arguments" to JsonPrimitive("synthetic changed native arguments")))
            } else {
                item
            }
        }
        var posted = ""
        fixture.manager.interceptor(turn(), null, disableParallel = false)
            .intercept(nativeRequest(changed), RecordingSink()) { request ->
                posted = request
                completedOutcome()
            }
        assertTrue(
            logLines.any { "history rewrite skipped record" in it && "native_branch=payload" in it },
            "a proven native payload rejection, not a missing-history control, must precede retirement",
        )
        return posted
    }

    private fun registry(manager: CodexCodeModeBridge): CodexCodeModeRegistry {
        val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
        return field.get(manager) as CodexCodeModeRegistry
    }

    private fun ownedCallbacks(readId: String): List<JsonElement> =
        Json.parseToJsonElement(requestWithResult(readId, "A")).jsonObject.getValue("input").jsonArray.drop(1)

    private fun nativeResult(readId: String): List<JsonElement> =
        Json.parseToJsonElement(baselineWithNativeSearch()).jsonObject.getValue("input").jsonArray + ownedCallbacks(readId)

    private fun nativeRequest(input: List<JsonElement>): String =
        JsonObject(
            mapOf(
                "input" to JsonArray(input),
                "prompt_cache_key" to JsonPrimitive("splice-synthetic-native-retirement"),
            ),
        ).toString()

    private fun baselineWithNativeSearch(): String =
        """
        {"input":[
          {"role":"developer","content":"s"},
          {"role":"user","content":"start"},
          {"type":"tool_search_call","call_id":"known-search","arguments":{"query":"release"}},
          {"type":"tool_search_output","call_id":"known-search","tools":[
            {"type":"function","name":"mcp__release__lookup"}
          ]}
        ]}
        """.trimIndent()
}
