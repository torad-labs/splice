import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep

class CodexCodeModeBridgeTest : CodeModeBridgeTestSupport() {
    @Test
    fun `immediate completion rewrites opaque pair and aggregates real upstream usage`() = runTest {
        val bridge = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("answer")))))
        var upstreamCalls = 0
        var rewritten = ""
        val outcome = bridge.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                upstreamCalls++
                if (upstreamCalls == 1) {
                    TurnOutcome.Success(false, false, Usage(10, 2, 3, 1), customCalls = listOf(outer()))
                } else {
                    rewritten = body
                    TurnOutcome.Success(false, false, Usage(20, 4, 5, 2), bodyText = "final", messageClosed = true)
                }
            } as TurnOutcome.Success

        assertEquals(2, upstreamCalls)
        assertTrue("custom_tool_call" in rewritten)
        assertTrue("outer-call" in rewritten)
        assertTrue("answer" in rewritten)
        assertEquals("final", outcome.bodyText)
        assertEquals(Usage(20, 6, 5, 3), outcome.usage)
    }

    @Test
    fun `consecutive completed scripts continue through one upstream loop`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("one"))),
                    ArrayDeque(listOf(CodeModeStep.Completed("two"))),
                ),
            ),
        )
        val bridge = bridge(runtime)
        var calls = 0
        var finalBody = ""
        val outcome = bridge.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                calls++
                when (calls) {
                    1 -> TurnOutcome.Success(
                        false,
                        false,
                        Usage(1, 1),
                        customCalls = listOf(outer("outer-1", "first")),
                    )
                    2 -> TurnOutcome.Success(
                        false,
                        false,
                        Usage(2, 1),
                        customCalls = listOf(outer("outer-2", "second")),
                    )
                    else -> {
                        finalBody = body
                        completedOutcome()
                    }
                }
            }

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(3, calls)
        assertEquals(listOf("first", "second"), runtime.sources)
        assertTrue("outer-1" in finalBody && "outer-2" in finalBody)
        assertTrue("one" in finalBody && "two" in finalBody)
    }

    @Test
    fun `dependent calls cross client requests without an intervening upstream call`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-1", "Read", "path" to "a"))),
                    CodeModeStep.Calls(listOf(call("runtime-2", "Edit", "file" to "b"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val bridge = bridge(runtime)
        val sink1 = RecordingSink()
        val first = bridge.interceptor(turn(), outer(), disableParallel = false)
        var upstreamCalls = 0

        val firstOutcome = first.intercept(BASE_REQUEST, sink1) {
            upstreamCalls++
            TurnOutcome.Success(false, false, Usage(inputTokens = 100), customCalls = listOf(outer()))
        }
        assertTrue((firstOutcome as TurnOutcome.Success).hasToolUse)
        assertEquals(100, firstOutcome.usage.inputTokens)
        assertEquals(listOf("Read"), sink1.tools.map { it.name })
        assertEquals(1, upstreamCalls)

        val readId = sink1.tools.single().id
        val sink2 = RecordingSink()
        val second = bridge.interceptor(turn(resultId = readId, result = "A"), null, disableParallel = false)
        val secondOutcome = second.intercept(requestWithResult(readId, "A"), sink2) {
            upstreamCalls++
            completedOutcome()
        }
        assertTrue((secondOutcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("Edit"), sink2.tools.map { it.name })
        assertEquals(1, upstreamCalls, "resuming the cell must not call the model")

        val editId = sink2.tools.single().id
        val sink3 = RecordingSink()
        val third = bridge.interceptor(turn(resultId = editId, result = "B"), null, disableParallel = false)
        val thirdOutcome = third.intercept(requestWithTwoResults(readId, editId), sink3) { rewritten ->
            upstreamCalls++
            val input = Json.parseToJsonElement(rewritten).jsonObject.getValue("input").toString()
            assertTrue("custom_tool_call" in input)
            assertTrue("custom_tool_call_output" in input)
            assertFalse(readId in input)
            assertFalse(editId in input)
            completedOutcome()
        }
        assertFalse((thirdOutcome as TurnOutcome.Success).hasToolUse)
        assertEquals(2, upstreamCalls)
        assertEquals(listOf("runtime-1", "runtime-2"), runtime.cell.results.flatten().map { it.id })
    }

    @Test
    fun `immediate history survives a later cell with dependent callbacks`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("one"))),
                    ArrayDeque(
                        listOf(
                            CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                            CodeModeStep.Calls(listOf(call("runtime-edit", "Edit"))),
                            CodeModeStep.Completed("two"),
                        ),
                    ),
                ),
            ),
        )
        val manager = bridge(runtime)
        val readSink = RecordingSink()
        var initialPosts = 0
        val first = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, readSink) {
                initialPosts++
                if (initialPosts == 1) outerOutcome("outer-a") else outerOutcome("outer-b")
            }
        assertTrue((first as TurnOutcome.Success).hasToolUse)
        assertEquals(2, initialPosts)

        val readId = readSink.tools.single().id
        val editSink = RecordingSink()
        val second = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(readId, "A"), editSink) { error("upstream must not run") }
        assertTrue((second as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("Edit"), editSink.tools.map { it.name })
        assertEquals(2, initialPosts)

        val editId = editSink.tools.single().id
        val finalTurn = turn(
            results = listOf(CodeModeResult(readId, "A"), CodeModeResult(editId, "B")),
        )
        var finalPost = ""
        val third = manager.interceptor(finalTurn, null, disableParallel = false)
            .intercept(requestWithTwoResults(readId, editId), RecordingSink()) { body ->
                finalPost = body
                completedOutcome()
            }

        assertTrue(third is TurnOutcome.Success)
        assertTrue("outer-a" in finalPost)
        assertTrue("one" in finalPost)
        assertTrue("outer-b" in finalPost)
        assertTrue("two" in finalPost)
    }

    @Test
    fun `callback history keeps its position across a user turn and later cell`() = runTest {
        val runtime = crossTurnRuntime()
        val manager = bridge(runtime)
        val aSink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, aSink) { outerOutcome("outer-a") }
        val aReadId = aSink.tools.single().id
        manager.interceptor(turn(aReadId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(aReadId, "A"), RecordingSink()) { completedOutcome() }

        val userMessage = "start the second cell"
        val startBBody =
            """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$aReadId","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$aReadId","output":"A"},{"role":"user","content":"$userMessage"}]}"""
        val aResult = CodeModeResult(aReadId, "A")
        val bReadSink = RecordingSink()
        manager.interceptor(turn(results = listOf(aResult)), null, disableParallel = false)
            .intercept(startBBody, bReadSink) { rewritten ->
                assertTrue(rewritten.indexOf("outer-a") < rewritten.indexOf(userMessage))
                assertFalse(aReadId in rewritten)
                outerOutcome("outer-b")
            }
        val bReadId = bReadSink.tools.single().id

        val resumeBBody =
            """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$aReadId","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$aReadId","output":"A"},{"role":"user","content":"$userMessage"},{"type":"function_call","call_id":"$bReadId","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$bReadId","output":"B"}]}"""
        val bReadResult = CodeModeResult(bReadId, "B")
        val bEditSink = RecordingSink()
        val resumed = manager.interceptor(
            turn(results = listOf(aResult, bReadResult)),
            null,
            disableParallel = false,
        ).intercept(resumeBBody, bEditSink) { error("upstream must not run") }
        assertTrue((resumed as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("Edit"), bEditSink.tools.map { it.name })

        val bEditId = bEditSink.tools.single().id
        val finalBody =
            """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$aReadId","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$aReadId","output":"A"},{"role":"user","content":"$userMessage"},{"type":"function_call","call_id":"$bReadId","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$bReadId","output":"B"},{"type":"function_call","call_id":"$bEditId","name":"Edit","arguments":"{}"},{"type":"function_call_output","call_id":"$bEditId","output":"C"}]}"""
        val finalTurn = turn(
            results = listOf(aResult, bReadResult, CodeModeResult(bEditId, "C")),
        )
        var finalPost = ""
        manager.interceptor(finalTurn, null, disableParallel = false)
            .intercept(finalBody, RecordingSink()) { body ->
                finalPost = body
                completedOutcome()
            }

        assertFalse(aReadId in finalPost)
        assertTrue(finalPost.indexOf("outer-a") < finalPost.indexOf(userMessage))
        assertTrue(finalPost.indexOf(userMessage) < finalPost.indexOf("outer-b"))
        assertEquals(2, Regex("outer-a").findAll(finalPost).count())
    }

    @Test
    fun `client parallel disable exposes one runtime call at a time`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(
                        listOf(
                            call("r1", "Read", "path" to "a"),
                            call("r2", "Read", "path" to "b"),
                        ),
                    ),
                    CodeModeStep.Completed("ok"),
                ),
            ),
        )
        val bridge = bridge(runtime)
        val sink = RecordingSink()
        bridge.interceptor(turn(), outer(), disableParallel = true).intercept(BASE_REQUEST, sink) { outerOutcome() }
        assertEquals(1, sink.tools.size)

        val firstId = sink.tools.single().id
        val nextSink = RecordingSink()
        bridge.interceptor(turn(resultId = firstId, result = "one"), null, disableParallel = true)
            .intercept(requestWithResult(firstId, "one"), nextSink) { error("upstream must not run") }
        assertEquals(1, nextSink.tools.size)
        assertEquals(1, runtime.cell.advances, "the runtime waits until the complete yielded batch returns")
    }

    @Test
    fun `passive sibling content before an owned result is preserved and interrupts further callbacks`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-1", "Read"))),
                    CodeModeStep.Calls(listOf(call("runtime-2", "Edit"))),
                ),
            ),
        )
        val bridge = bridge(runtime)
        val firstSink = RecordingSink()
        bridge.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, firstSink) { outerOutcome() }
        val readId = firstSink.tools.single().id
        val sibling = "Background job probe-job completed: 7 times 8 = 56."
        var forwarded = ""

        val outcome = bridge.interceptor(turn(resultId = readId, result = "A"), null, disableParallel = false)
            .intercept(requestWithSiblingBeforeResult(readId, sibling), RecordingSink()) { rewritten ->
                forwarded = rewritten
                completedOutcome()
            }

        assertFalse((outcome as TurnOutcome.Success).hasToolUse)
        assertTrue(sibling in forwarded)
        assertTrue("custom_tool_call_output" in forwarded)
        assertFalse("\"call_id\":\"$readId\"" in forwarded)
        assertEquals(1, runtime.cell.advances, "new client content interrupts before any further JavaScript runs")
        assertTrue(runtime.cell.closed)
    }

    @Test
    fun `turn builder arms only canonical noncompact lite requests`() {
        val manager = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("ok")))))
        val builder = CodexCodeModeTurnBuilder(manager)
        listOf("gpt-6-astra", "gpt-6-sol", "gpt-6-astra[1m]", "GPT-6-SOL[500K]").forEach { model ->
            val prepared = builder.prepare(toolBody(), false, "s", built(model, lite = true))
            assertTrue(prepared.roundInterceptor != null, model)
        }
        listOf("not-gpt-6-astra", "gpt-6-astra-preview", "gpt-6-astra[preview]").forEach { model ->
            val prepared = builder.prepare(toolBody(), false, "s", built(model, lite = true))
            assertTrue(prepared.roundInterceptor == null, model)
        }
        val compact = builder.prepare(toolBody(), true, "s", built("gpt-6-astra", lite = true))
        val toolless = builder.prepare(toollessBody(), false, "s", built("gpt-6-astra", lite = true))
        val nonLite = builder.prepare(toolBody(), false, "s", built("gpt-6-astra", lite = false))
        val named = builder.prepare(namedChoiceBody(), false, "s", built("gpt-6-astra", lite = true))
        assertTrue(compact.roundInterceptor == null)
        assertTrue(toolless.roundInterceptor == null)
        assertTrue(nonLite.roundInterceptor == null)
        assertTrue(named.roundInterceptor == null)
    }

    @Test
    fun `turn builder rejects nontext result content`() {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("ok"))))
        val builder = CodexCodeModeTurnBuilder(bridge(runtime))
        val error = assertThrows(IllegalArgumentException::class.java) {
            builder.prepare(nonTextResultBody(), false, "s", built("gpt-6-astra", lite = true))
        }
        assertTrue(error.message.orEmpty().contains("unsupported non-text content"))
    }

    @Test
    fun `historical nonbridge image result passes without bridge conversion`() {
        val manager = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("ok")))))
        val built = CodexCodeModeTurnBuilder(manager).prepare(
            historicalImageResultBody(),
            compact = false,
            sessionId = "s",
            built = built("gpt-6-astra", lite = true),
        )

        assertTrue(built.roundInterceptor != null)
    }

    @Test
    fun `unknown current tool is refused before runtime receives results`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Gone"))))))
        val outcome = bridge(runtime).interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("not in the current tool catalog"))
        assertEquals(1, runtime.cell.advances)
    }

    private fun crossTurnRuntime(): QueuedRuntime = QueuedRuntime(
        ArrayDeque(
            listOf(
                ArrayDeque(
                    listOf(
                        CodeModeStep.Calls(listOf(call("runtime-a-read", "Read"))),
                        CodeModeStep.Completed("one"),
                    ),
                ),
                ArrayDeque(
                    listOf(
                        CodeModeStep.Calls(listOf(call("runtime-b-read", "Read"))),
                        CodeModeStep.Calls(listOf(call("runtime-b-edit", "Edit"))),
                        CodeModeStep.Completed("two"),
                    ),
                ),
            ),
        ),
    )
}
